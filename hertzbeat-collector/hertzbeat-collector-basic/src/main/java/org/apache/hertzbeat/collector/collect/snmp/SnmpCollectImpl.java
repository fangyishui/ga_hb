/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hertzbeat.collector.collect.snmp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.apache.hertzbeat.collector.collect.AbstractCollect;
import org.apache.hertzbeat.collector.constants.CollectorConstants;
import org.apache.hertzbeat.collector.dispatch.DispatchConstants;
import org.apache.hertzbeat.collector.util.CollectUtil;
import org.apache.hertzbeat.common.constants.CommonConstants;
import org.apache.hertzbeat.common.entity.job.Metrics;
import org.apache.hertzbeat.common.entity.job.protocol.SnmpProtocol;
import org.apache.hertzbeat.common.entity.message.CollectRep;
import org.apache.hertzbeat.common.util.CommonUtil;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.UserTarget;
import org.snmp4j.fluent.SnmpBuilder;
import org.snmp4j.fluent.SnmpCompletableFuture;
import org.snmp4j.fluent.TargetBuilder;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.AuthMD5;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.PrivDES;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityModel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.util.DefaultPDUFactory;
import org.snmp4j.util.TableEvent;
import org.snmp4j.util.TableUtils;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Snmp protocol collection implementation
 */
@Slf4j
public class SnmpCollectImpl extends AbstractCollect {

    private static final String AES128 = "1";
    private static final String SHA1 = "1";
    private static final String DEFAULT_PROTOCOL = "udp";
    private static final String OPERATION_GET = "get";
    private static final String OPERATION_WALK = "walk";
    private static final String HEX_SPLIT = ":";
    private static final String FORMAT_PATTERN =
            "{0,choice,0#|1#1 day, |1<{0,number,integer} days, }"
                    + "{1,choice,0#|1#1 hour, |1<{1,number,integer} hours, }"
                    + "{2,choice,0#|1#1 minute, |1<{2,number,integer} minutes, }"
                    + "{3,choice,0#|1#1 second, |1<{3,number,integer} seconds }";
    private static final String DATE_AND_TIME_PATTERN = "%04d-%02d-%02d,%02d:%02d:%02d.%d";
    private static final String TIME_ZONE_PATTERN = "%c%02d:%02d";

    private final Map<Integer, Snmp> versionSnmpService = new ConcurrentHashMap<>(3);

    @Override
    public void preCheck(Metrics metrics) throws IllegalArgumentException {
        if (metrics == null || metrics.getSnmp() == null) {
            throw new IllegalArgumentException("Snmp collect must has snmp params");
        }
        SnmpProtocol snmpProtocol = metrics.getSnmp();
        Assert.hasText(snmpProtocol.getHost(), "snmp host is required.");
        Assert.hasText(snmpProtocol.getPort(), "snmp port is required.");
        Assert.notNull(snmpProtocol.getVersion(), "snmp version is required.");
    }

    @Override
    public void collect(CollectRep.MetricsData.Builder builder, Metrics metrics) {
        long startTime = System.currentTimeMillis();
        SnmpProtocol snmpProtocol = metrics.getSnmp();
        int timeout = CollectUtil.getTimeout(snmpProtocol.getTimeout());
        int snmpVersion = getSnmpVersion(snmpProtocol.getVersion());
        Snmp snmpService = null;
        try {
            SnmpBuilder snmpBuilder = new SnmpBuilder();
            snmpService = getSnmpService(snmpVersion, snmpBuilder);
            snmpService.listen();
            Target<?> target;
            Address targetAddress = GenericAddress.parse(DEFAULT_PROTOCOL + ":" + snmpProtocol.getHost()
                    + "/" + snmpProtocol.getPort());
            TargetBuilder<?> targetBuilder = snmpBuilder.target(targetAddress);
            if (snmpVersion == SnmpConstants.version3) {
                // 智能判断安全级别：根据 privPassphrase/authPassphrase 是否为空决定
                boolean hasPriv = StringUtils.hasText(snmpProtocol.getPrivPassphrase());
                boolean hasAuth = StringUtils.hasText(snmpProtocol.getAuthPassphrase());

                OctetString userName = new OctetString(snmpProtocol.getUsername());
                // 根据安全级别分别构建 target，避免空密码导致 / by zero
                if (hasAuth && hasPriv) {
                    // authPriv: 认证 + 加密（使用 fluent builder）
                    target = targetBuilder
                            .user(snmpProtocol.getUsername())
                            .auth(getAuthPasswordEncryption(snmpProtocol.getAuthPasswordEncryption()))
                            .authPassphrase(snmpProtocol.getAuthPassphrase())
                            .priv(getPrivPasswordEncryption(snmpProtocol.getPrivPasswordEncryption()))
                            .privPassphrase(snmpProtocol.getPrivPassphrase())
                            .done()
                            .timeout(timeout).retries(1)
                            .build();
                } else {
                    // authNoPriv 或 noAuthNoPriv: 手动构建 UserTarget，避免 fluent builder 空密码问题
                    UserTarget userTarget = new UserTarget();
                    userTarget.setAddress(targetAddress);
                    userTarget.setSecurityName(userName);
                    userTarget.setTimeout(timeout);
                    userTarget.setRetries(1);
                    if (hasAuth) {
                        // authNoPriv: 仅认证
                        userTarget.setSecurityLevel(SecurityLevel.AUTH_NOPRIV);
                    } else {
                        // noAuthNoPriv: 无认证无加密
                        userTarget.setSecurityLevel(SecurityLevel.NOAUTH_NOPRIV);
                    }
                    target = userTarget;
                }
                USM usm = new USM(SecurityProtocols.getInstance(), new OctetString(MPv3.createLocalEngineID()), 0);
                SecurityModels.getInstance().addSecurityModel(usm);

                // 根据 hasAuth/hasPriv 创建对应的 UsmUser（与 target 构建逻辑一致）
                if (hasAuth && hasPriv) {
                    // authPriv: 认证 + 加密
                    snmpService.getUSM().addUser(userName,
                            new UsmUser(userName,
                                    getAuthProtocolOid(snmpProtocol.getAuthPasswordEncryption()),
                                    new OctetString(snmpProtocol.getAuthPassphrase()),
                                    getPrivProtocolOid(snmpProtocol.getPrivPasswordEncryption()),
                                    new OctetString(snmpProtocol.getPrivPassphrase())));
                } else if (hasAuth) {
                    // authNoPriv: 仅认证，不加密（Windows服务器场景）
                    snmpService.getUSM().addUser(userName,
                            new UsmUser(userName,
                                    getAuthProtocolOid(snmpProtocol.getAuthPasswordEncryption()),
                                    new OctetString(snmpProtocol.getAuthPassphrase()),
                                    null,
                                    null));
                } else {
                    // noAuthNoPriv: 无认证无加密
                    snmpService.getUSM().addUser(userName,
                            new UsmUser(userName,
                                    null,
                                    null,
                                    null,
                                    null));
                }
            } else if (snmpVersion == SnmpConstants.version1) {
                target = targetBuilder
                        .v1()
                        .community(new OctetString(snmpProtocol.getCommunity()))
                        .timeout(timeout).retries(1)
                        .build();
                target.setSecurityModel(SecurityModel.SECURITY_MODEL_SNMPv1);
            } else {
                target = targetBuilder
                        .v2c()
                        .community(new OctetString(snmpProtocol.getCommunity()))
                        .timeout(timeout).retries(1)
                        .build();
                target.setSecurityModel(SecurityModel.SECURITY_MODEL_SNMPv2c);
            }
            String operation = snmpProtocol.getOperation();
            operation = StringUtils.hasText(operation) ? operation : OPERATION_GET;
            if (OPERATION_GET.equalsIgnoreCase(operation)) {
                String contextName = getContextName(snmpProtocol.getContextName());
                PDU pdu = targetBuilder.pdu().type(PDU.GET).oids(snmpProtocol.getOids().values().toArray(new String[0])).contextName(contextName).build();
                SnmpCompletableFuture snmpRequestFuture = SnmpCompletableFuture.send(snmpService, target, pdu);
                List<VariableBinding> vbs = snmpRequestFuture.get().getAll();
                long responseTime = System.currentTimeMillis() - startTime;
                Map<String, String> oidsMap = snmpProtocol.getOids();
                Map<String, String> oidsValueMap = new HashMap<>(oidsMap.size());
                for (VariableBinding binding : vbs) {
                    if (binding == null) {
                        continue;
                    }
                    Variable variable = binding.getVariable();
                    if (variable instanceof TimeTicks timeTicks) {
                        String value = timeTicks.toString(FORMAT_PATTERN);
                        oidsValueMap.put(binding.getOid().toDottedString(), value);
                    } else {
                        oidsValueMap.put(binding.getOid().toDottedString(), binding.toValueString());
                    }
                }
                CollectRep.ValueRow.Builder valueRowBuilder = CollectRep.ValueRow.newBuilder();
                for (String alias : metrics.getAliasFields()) {
                    if (CollectorConstants.RESPONSE_TIME.equalsIgnoreCase(alias)) {
                        valueRowBuilder.addColumn(Long.toString(responseTime));
                    } else {
                        String oid = oidsMap.get(alias);
                        String value = oidsValueMap.get(oid);
                        valueRowBuilder.addColumn(Objects.requireNonNullElse(value, CommonConstants.NULL_VALUE));
                    }
                }
                builder.addValueRow(valueRowBuilder.build());
            } else if (OPERATION_WALK.equalsIgnoreCase(operation)) {
                Map<String, String> oidMap = snmpProtocol.getOids();
                Assert.notEmpty(oidMap, "snmp oids is required when operation is walk.");
                TableUtils tableUtils = new TableUtils(snmpService, new DefaultPDUFactory(PDU.GETBULK));
                OID[] oids = oidMap.values().stream().map(OID::new).toArray(OID[]::new);
                List<TableEvent> tableEvents = tableUtils.getTable(target, oids, null, null);
                Assert.notNull(tableEvents, "snmp walk response empty content.");
                long responseTime = System.currentTimeMillis() - startTime;
                for (TableEvent tableEvent : tableEvents) {
                    if (tableEvent == null || tableEvent.isError()) {
                        continue;
                    }
                    VariableBinding[] varBindings = tableEvent.getColumns();
                    Map<String, String> oidsValueMap = new HashMap<>(varBindings.length);
                    for (VariableBinding binding : varBindings) {
                        if (binding == null) {
                            continue;
                        }
                        Variable variable = binding.getVariable();
                        if (variable instanceof TimeTicks timeTicks) {
                            String value = timeTicks.toString(FORMAT_PATTERN);
                            oidsValueMap.put(binding.getOid().trim().toDottedString(), value);
                        } else {
                            oidsValueMap.put(binding.getOid().trim().toDottedString(), bingdingHexValueToString(binding));
                        }
                    }
                    // when too many empty value field, ignore
                    if (oidsValueMap.size() < metrics.getAliasFields().size() / 2) {
                        continue;
                    }
                    CollectRep.ValueRow.Builder valueRowBuilder = CollectRep.ValueRow.newBuilder();
                    for (String alias : metrics.getAliasFields()) {
                        if (CollectorConstants.RESPONSE_TIME.equalsIgnoreCase(alias)) {
                            valueRowBuilder.addColumn(Long.toString(responseTime));
                        } else {
                            String oid = oidMap.get(alias);
                            String value = oidsValueMap.get(oid);
                            if (value == null) {
                                // get leaf
                                for (String key : oidsValueMap.keySet()) {
                                    if (key.startsWith(oid)) {
                                        value = oidsValueMap.get(key);
                                        break;
                                    }
                                }
                            }
                            valueRowBuilder.addColumn(Objects.requireNonNullElse(value, CommonConstants.NULL_VALUE));
                        }
                    }
                    builder.addValueRow(valueRowBuilder.build());
                }
            }
        } catch (ExecutionException | InterruptedException ex) {
            String errorMsg = CommonUtil.getMessageFromThrowable(ex);
            log.warn("[snmp collect] error: {}", errorMsg);
            builder.setCode(CollectRep.Code.UN_CONNECTABLE);
            builder.setMsg(errorMsg);
        } catch (Exception e) {
            String errorMsg = CommonUtil.getMessageFromThrowable(e);
            log.warn("[snmp collect] error: {}", errorMsg, e);
            builder.setCode(CollectRep.Code.FAIL);
            builder.setMsg(errorMsg);
        } finally {
            if (snmpService != null) {
                if (snmpVersion == SnmpConstants.version3) {
                    try {
                        snmpClose(snmpService, SnmpConstants.version3);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }

    @Override
    public String supportProtocol() {
        return DispatchConstants.PROTOCOL_SNMP;
    }

    private synchronized Snmp getSnmpService(int snmpVersion, SnmpBuilder snmpBuilder) throws IOException {
        Snmp snmpService = versionSnmpService.get(snmpVersion);
        if (snmpService != null) {
            return snmpService;
        }
        if (snmpVersion == SnmpConstants.version3) {
            snmpService = snmpBuilder.udp().v3().securityProtocols(SecurityProtocols.SecurityProtocolSet.maxCompatibility).usm().threads(4).build();
        } else if (snmpVersion == SnmpConstants.version1) {
            snmpService = snmpBuilder.udp().v1().threads(4).build();
        } else {
            snmpService = snmpBuilder.udp().v2c().threads(4).build();
        }
        versionSnmpService.put(snmpVersion, snmpService);
        return snmpService;
    }

    private int getSnmpVersion(String snmpVersion) {
        int version = SnmpConstants.version2c;
        if (!StringUtils.hasText(snmpVersion)) {
            return version;
        }
        if (snmpVersion.equalsIgnoreCase(String.valueOf(SnmpConstants.version1))
                || snmpVersion.equalsIgnoreCase(TargetBuilder.SnmpVersion.v1.name())) {
            return SnmpConstants.version1;
        }
        if (snmpVersion.equalsIgnoreCase(String.valueOf(SnmpConstants.version3))
                || snmpVersion.equalsIgnoreCase(TargetBuilder.SnmpVersion.v3.name())) {
            return SnmpConstants.version3;
        }
        return version;
    }

    private String bingdingHexValueToString(VariableBinding binding) {
        // whether if binding is hex
        String hexString = binding.toValueString();
        if (hexString.contains(HEX_SPLIT)) {
            try {
                String clearHexStr = hexString.replace(HEX_SPLIT, "");
                byte[] bytes = HexFormat.of().parseHex(clearHexStr);
                CharsetDecoder decoder = Charset.forName("GB2312").newDecoder();
                try {
                    CharBuffer res = decoder.decode(ByteBuffer.wrap(bytes));
                    return res.toString();
                } catch (Exception e) {
                    if (isDateAndTimeOctetString(binding)) {
                        return parseDateAndTime(clearHexStr);
                    }
                    return new String(bytes);
                }
            } catch (Exception e) {
                return hexString;
            }
        } else {
            return hexString;
        }
    }

    private static boolean isDateAndTimeOctetString(VariableBinding binding) {
        if (!(binding.getVariable() instanceof OctetString)) {
            return false;
        }
        byte[] bytes = HexFormat.of().parseHex(binding.toValueString().replaceAll(HEX_SPLIT, ""));
        if (bytes.length != 8 && bytes.length != 11) return false;

        int year = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
        if (year < 1970 || year > 3000) return false;

        int month = bytes[2] & 0xFF;
        if (month < 1 || month > 12) return false;

        int day = bytes[3] & 0xFF;
        if (day < 1 || day > 31) return false;

        int hour = bytes[4] & 0xFF;
        if (hour > 23) return false;

        int minute = bytes[5] & 0xFF;
        if (minute > 59) return false;

        int second = bytes[6] & 0xFF;
        if (second > 59) return false;

        int deciSecond = bytes[7] & 0xFF;
        if (deciSecond > 99) return false;

        if (bytes.length == 11) {
            int tzSign = bytes[8] & 0xFF;
            if (tzSign != 0x2B && tzSign != 0x2D) return false;

            int tzHour = bytes[9] & 0xFF;
            if (tzHour > 23) return false;

            int tzMinute = bytes[10] & 0xFF;
            if (tzMinute > 59) return false;
        }
        return true;
    }

    private static String parseDateAndTime(String hexString) {
        byte[] bytes = HexFormat.of().parseHex(hexString);
        int year = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
        int month = bytes[2] & 0xFF;
        int day = bytes[3] & 0xFF;
        int hour = bytes[4] & 0xFF;
        int minute = bytes[5] & 0xFF;
        int second = bytes[6] & 0xFF;
        int deciSeconds = bytes[7] & 0xFF;
        String dateTime = String.format(DATE_AND_TIME_PATTERN,
                year, month, day, hour, minute, second, deciSeconds);
        if (bytes.length == 11) {
            char sign = (char) bytes[8];
            int tzHour = bytes[9] & 0xFF;
            int tzMinute = bytes[10] & 0xFF;
            dateTime += String.format(" " + TIME_ZONE_PATTERN, sign, tzHour, tzMinute);
        }
        return dateTime;
    }


    private void snmpClose(Snmp snmp, int version) throws IOException {
        snmp.close();
        versionSnmpService.remove(version);
    }

    private TargetBuilder.PrivProtocol getPrivPasswordEncryption(String privPasswordEncryption) {
        if (privPasswordEncryption == null) {
            return TargetBuilder.PrivProtocol.des;
        } else if (AES128.equals(privPasswordEncryption)) {
            return TargetBuilder.PrivProtocol.aes128;
        } else {
            return TargetBuilder.PrivProtocol.des;
        }
    }

    private TargetBuilder.AuthProtocol getAuthPasswordEncryption(String authPasswordEncryption) {
        if (authPasswordEncryption == null) {
            return TargetBuilder.AuthProtocol.md5;
        } else if (SHA1.equals(authPasswordEncryption)) {
            return TargetBuilder.AuthProtocol.sha1;
        } else {
            return TargetBuilder.AuthProtocol.md5;
        }
    }

    /**
     * 根据配置获取认证协议 OID（用于 UsmUser）
     * null或非1 -> MD5, 1 -> SHA1
     */
    private OID getAuthProtocolOid(String authPasswordEncryption) {
        if (SHA1.equals(authPasswordEncryption)) {
            return AuthSHA.ID;
        }
        return AuthMD5.ID;
    }

    /**
     * 根据配置获取加密协议 OID（用于 UsmUser）
     * null或非1 -> DES, 1 -> AES128
     */
    private OID getPrivProtocolOid(String privPasswordEncryption) {
        if (AES128.equals(privPasswordEncryption)) {
            return PrivAES128.ID;
        }
        return PrivDES.ID;
    }

    private String getContextName(String contextName) {
        return contextName == null ? "" : contextName;
    }
}
