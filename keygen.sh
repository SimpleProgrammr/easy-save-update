#!/usr/bin/env bash
keytool -genkeypair \
  -alias kosciuszkon \
  -keyalg RSA \
  -keysize 2048 \
  -validity 365 \
  -keystore kosciuszkon.jks \
  -storepass HASSLO123 \
  -keypass HASSLO123 \
  -dname "CN=XD, OU=IT, O=Kosciuszkon26, L=Krakow, ST=Malopolska, C=PL"