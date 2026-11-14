#!/usr/bin/env bash
# install-java-bootstrap.sh: optional EMR bootstrap action. Installs Amazon Corretto
# (17 by default, or 21) and records its JAVA_HOME in /etc/scholarchain/java.env, which
# scripts/emr/lib-emr.sh reads. Used only when the capability check finds no JDK 17 or
# later (UNVERIFIED whether emr-7.10.0 defaults to JDK 17), and only when
# emr_install_java_bootstrap is true. A failing bootstrap action fails the cluster
# launch, which is why it is off by default.
#
# Usage (EMR runs it as root): bash install-java-bootstrap.sh [17|21]
# S3 key: bootstrap/install-java-bootstrap.sh in the artifacts bucket (uploaded by
# scripts/upload-inputs.sh; the emr_ec2 role reads bootstrap/*).
#
# The nodes sit in private subnets with no NAT: the package install works only if the
# Amazon Linux repositories are reachable through the S3 gateway endpoint (the AL2023
# and AL2 repositories are S3-backed, UNVERIFIED for this subnet and endpoint policy).
# If the install fails the launch fails with the message below in the bootstrap log.
set -euo pipefail

major="${1:-17}"
case "$major" in
  17 | 21) ;;
  *) echo "EVIDENCE: major must be 17 or 21, got: $major" >&2; exit 1 ;;
esac

printf 'STAGE[1] install-corretto-%s: start\n' "$major"
if command -v dnf >/dev/null 2>&1; then
  dnf install -y "java-${major}-amazon-corretto-devel"
else
  yum install -y "java-${major}-amazon-corretto-devel"
fi
printf 'STAGE[1] install-corretto-%s: ok\n' "$major"

printf 'STAGE[2] record-java-home: start\n'
jhome=""
for d in /usr/lib/jvm/java-"${major}"-amazon-corretto* /usr/lib/jvm/java-"${major}"*; do
  if [[ -x "$d/bin/java" ]]; then jhome="$d"; break; fi
done
[[ -n "$jhome" ]] || { echo "EVIDENCE: no Corretto $major directory under /usr/lib/jvm after the install" >&2; exit 1; }
mkdir -p /etc/scholarchain
printf 'JAVA_HOME=%s\n' "$jhome" > /etc/scholarchain/java.env
chmod 644 /etc/scholarchain/java.env
"$jhome/bin/java" -version
printf 'STAGE[2] record-java-home: ok\n'
