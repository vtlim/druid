# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

ARG JDK_VERSION=17-slim-bullseye
FROM openjdk:$JDK_VERSION as druidbase

# Bundle everything into one script so cleanup can reduce image size.
# Otherwise docker's layered images mean that things are not actually deleted.

COPY base-setup.sh /root/base-setup.sh
ARG KAFKA_VERSION
# ZooKeeper version to install in the base image
# This is passed in by maven at build time to align with the client version we depend on in the pom file
ARG ZK_VERSION
ARG APACHE_ARCHIVE_MIRROR_HOST=https://downloads.apache.org
RUN APACHE_ARCHIVE_MIRROR_HOST=${APACHE_ARCHIVE_MIRROR_HOST} /root/base-setup.sh && rm -f /root/base-setup.sh

FROM druidbase
ARG MYSQL_VERSION
ARG MARIA_VERSION
ARG MYSQL_DRIVER_CLASSNAME
ARG CONFLUENT_VERSION

# Verify Java version
RUN java -version

# Add Druid scripts and jars
ADD bin/* /usr/local/druid/bin/
ADD lib/* /usr/local/druid/lib/
COPY extensions/ /usr/local/druid/extensions/

# Download the MySQL Java connector
# target path must match the exact path referenced in environment-configs/common
# alternatively: Download the MariaDB Java connector, and pretend it is the mysql connector
RUN ( \
    if [ "$MYSQL_DRIVER_CLASSNAME" = "com.mysql.jdbc.Driver" ]; then  \
      wget -q "https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/$MYSQL_VERSION/mysql-connector-j-$MYSQL_VERSION.jar"  -O /usr/local/druid/lib/mysql-connector-j.jar; \
    elif [ "$MYSQL_DRIVER_CLASSNAME" = "org.mariadb.jdbc.Driver" ]; then \
      wget -q "https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/$MARIA_VERSION/mariadb-java-client-$MARIA_VERSION.jar" -O /usr/local/druid/lib/mysql-connector-j.jar; \
    else \
      echo "Unknown MySQL driver class name: $MYSQL_DRIVER_CLASSNAME"; \
      exit 1; \
    fi \
    )

# download kafka protobuf provider
RUN wget -q "https://packages.confluent.io/maven/io/confluent/kafka-protobuf-provider/$CONFLUENT_VERSION/kafka-protobuf-provider-$CONFLUENT_VERSION.jar" \
    -O /usr/local/druid/lib/kafka-protobuf-provider.jar

# Setup metadata store
RUN echo "[mysqld]\ncharacter-set-server=utf8\ncollation-server=utf8_bin\n" >> /etc/mysql/my.cnf
RUN ( \
    service mariadb start; \
    for i in {30..0}; do \
      mysqladmin ping --silent && break; \
      sleep 1; \
    done; \
    if [ "$i" = 0 ]; then \
      echo "MySQL did not start"; exit 1; \
    fi; \
    echo "CREATE USER 'druid'@'%' IDENTIFIED BY 'diurd'; GRANT ALL ON druid.* TO 'druid'@'%'; CREATE database druid DEFAULT CHARACTER SET utf8mb4;" | mysql -u root; \
    /usr/local/druid/bin/run-java -cp "/usr/local/druid/lib/*" -Ddruid.extensions.directory=/usr/local/druid/extensions -Ddruid.extensions.loadList='["mysql-metadata-storage"]' -Ddruid.metadata.storage.type=mysql -Ddruid.metadata.mysql.driver.driverClassName=$MYSQL_DRIVER_CLASSNAME org.apache.druid.cli.Main tools metadata-init --connectURI="jdbc:mysql://localhost:3306/druid" --user=druid --password=diurd; \
    service mariadb stop \
    )

ADD test-data /test-data

# Setup supervisord
ADD supervisord.conf /etc/supervisor/conf.d/supervisord.conf

# Add druid configuration setup script
ADD druid.sh /druid.sh

# internal docker_ip:9092 endpoint is used to access Kafka from other Docker containers
# external docker ip:9093 endpoint is used to access Kafka from test code
# run this last to avoid rebuilding the image every time the ip changes
ADD docker_ip docker_ip
RUN perl -pi -e "s/#listeners=.*/listeners=INTERNAL:\/\/172.172.172.2:9092,EXTERNAL:\/\/172.172.172.2:9093/" /usr/local/kafka/config/server.properties
RUN perl -pi -e "s/#advertised.listeners=.*/advertised.listeners=INTERNAL:\/\/172.172.172.2:9092,EXTERNAL:\/\/$(cat docker_ip):9093/" /usr/local/kafka/config/server.properties
RUN perl -pi -e "s/#listener.security.protocol.map=.*/listener.security.protocol.map=INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT\ninter.broker.listener.name=INTERNAL/" /usr/local/kafka/config/server.properties
RUN perl

# Add directory with TLS support files
ADD tls tls

ADD client_tls client_tls

# Expose ports:
# - 8081, 8281: HTTP, HTTPS (coordinator)
# - 8082, 8282: HTTP, HTTPS (broker)
# - 8083, 8283: HTTP, HTTPS (historical)
# - 8090, 8290: HTTP, HTTPS (overlord)
# - 8091, 8291: HTTP, HTTPS (middlemanager)
# - 8888-8891, 9088-9091: HTTP, HTTPS (routers)
# - 3306: MySQL
# - 2181 2888 3888: ZooKeeper
# - 8100 8101 8102 8103 8104 8105 : peon ports
# - 8300 8301 8302 8303 8304 8305 : peon HTTPS ports
EXPOSE 8081 8281
EXPOSE 8082 8282
EXPOSE 8083 8283
EXPOSE 8090 8290
EXPOSE 8091 8291
EXPOSE 3306
EXPOSE 2181 2888 3888
EXPOSE 8100 8101 8102 8103 8104 8105
EXPOSE 8300 8301 8302 8303 8304 8305
EXPOSE 9092 9093

ENV MYSQL_DRIVER_CLASSNAME=$MYSQL_DRIVER_CLASSNAME
WORKDIR /var/lib/druid
ENTRYPOINT /tls/generate-server-certs-and-keystores.sh \
            && . /druid.sh \
            # Create druid service config files with all the config variables
            && setupConfig \
            # Some test groups require pre-existing data to be setup
            && setupData \
            # Export the service config file path to use in supervisord conf file
            && export DRUID_SERVICE_CONF_DIR="$(. /druid.sh; getConfPath ${DRUID_SERVICE})" \
            # Export the common config file path to use in supervisord conf file
            && export DRUID_COMMON_CONF_DIR="$(. /druid.sh; getConfPath _common)" \
            # Run Druid service using supervisord
            && exec /usr/bin/supervisord -c /etc/supervisor/conf.d/supervisord.conf
