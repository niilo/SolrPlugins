FROM maven:3.3.9-jdk-8
USER root
RUN echo "deb http://http.us.debian.org/debian testing main non-free contrib" >> /etc/apt/sources.list && \
  echo "deb-src http://http.us.debian.org/debian testing main non-free contrib" >> /etc/apt/sources.list && \
  apt-get update && apt-get -yqq install libvoikko1/testing && apt-get clean
RUN mkdir /etc/voikko && cd /etc/voikko && \
    wget http://www.puimula.org/htp/testing/voikko-snapshot-v5/dict-morphoid.zip && \
    unzip dict-morphoid.zip && rm dict-morphoid.zip
