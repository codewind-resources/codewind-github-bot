FROM ubuntu:18.04 as builder

RUN apt-get -y update && apt-get install -y openjdk-8-jdk maven git && rm -rf /var/lib/apt/lists/* 

ADD . /root

RUN cd /root \
	&& git clone https://github.com/jgwest/github-api-mirror \
	&& cd github-api-mirror \
	&& mvn --projects GitHubApiMirrorClient  --also-make install


RUN cd /root \
	&& git clone https://github.com/jgwest/zenhub-api-java-client/ \
	&& cd zenhub-api-java-client \
	&& mvn install -DskipTests


RUN cd /root  \
	&& mvn package -f pom.xml
	
RUN cp /root/target/CodewindGitHubBot-1.0-SNAPSHOT.jar /root/CodewindGitHubBot.jar

FROM adoptopenjdk:8-jre-openj9

# Create a default user to allow us to run as non-root
RUN useradd -u 1001 -r -g 0 -s /usr/sbin/nologin default 

RUN mkdir -p /home/default/data && \
	chown -R default /home/default/data

RUN mkdir -p /home/default/logs && \
        chown -R default /home/default/logs

ENV FILE_LOGGER_PATH /home/default/logs

USER 1001

VOLUME /home/default/data

COPY --chown=1001:0 --from=builder /root/CodewindGitHubBot.jar /home/default/CodewindGitHubBot.jar

ENTRYPOINT [ "java", "-jar", "/home/default/CodewindGitHubBot.jar" ]
