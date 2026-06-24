# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

FROM eclipse-temurin:21-jdk

MAINTAINER Apache HertzBeat "dev@hertzbeat.apache.org"

# Install SSH And Locales
# 使用阿里云镜像源加速国内下载
RUN sed -i 's@archive.ubuntu.com@mirrors.aliyun.com@g' /etc/apt/sources.list.d/ubuntu.sources && \
    sed -i 's@security.ubuntu.com@mirrors.aliyun.com@g' /etc/apt/sources.list.d/ubuntu.sources && \
    rm -f /var/cache/apt/archives/*.deb /var/cache/apt/archives/partial/*.deb /var/cache/apt/*.bin 2>/dev/null; \
    DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::AllowInsecureRepositories=true \
    -o Acquire::AllowDowngradeToInsecureRepositories=true \
    update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openssh-server locales && \
    rm -rf /var/lib/apt/lists/*
RUN mkdir /var/run/sshd
# Build zh_CN en_US locale resource package
RUN localedef -c -f UTF-8 -i zh_CN zh_CN.UTF-8
RUN localedef -c -f UTF-8 -i en_US en_US.UTF-8

ENV TZ=Asia/Shanghai
ENV LANG=en_US.UTF-8

ADD apache-hertzbeat-1.*-docker-bin.tar.gz /opt/

EXPOSE 1157 1158 22

WORKDIR /opt/hertzbeat/

ENTRYPOINT ["./bin/entrypoint.sh"]
