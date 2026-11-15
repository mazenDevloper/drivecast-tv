#!/usr/bin/env bash
# RustDesk's own server on a fresh Linux VM (Ubuntu / Debian; e.g. Oracle Cloud's Always Free, Google Cloud's e2-micro):
#   curl -fsSL https://raw.githubusercontent.com/mazenDevloper/drivecast-tv/main/tools/rustdesk/setup-server.sh | sudo bash
# Installs Docker, starts hbbs + hbbr, opens the ports in the VM's own firewall, and prints what the clients need.
# The cloud's own firewall (Oracle: «Security List», Google: «VPC firewall») must allow the same ports too:
#   TCP 21115-21119, UDP 21116
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "run with sudo"; exit 1; }
DIR=/opt/rustdesk
command -v docker >/dev/null || curl -fsSL https://get.docker.com | sh
mkdir -p "$DIR/data"
curl -fsSL https://raw.githubusercontent.com/mazenDevloper/drivecast-tv/main/tools/rustdesk/docker-compose.yml -o "$DIR/docker-compose.yml"
# the VM's own firewall (Oracle's Ubuntu images block everything but SSH with iptables)
if command -v ufw >/dev/null && ufw status | grep -q active; then
  ufw allow 21115:21119/tcp; ufw allow 21116/udp
fi
if command -v iptables >/dev/null; then
  for p in 21115 21116 21117 21118 21119; do iptables -C INPUT -p tcp --dport $p -j ACCEPT 2>/dev/null || iptables -I INPUT -p tcp --dport $p -j ACCEPT; done
  iptables -C INPUT -p udp --dport 21116 -j ACCEPT 2>/dev/null || iptables -I INPUT -p udp --dport 21116 -j ACCEPT
  command -v netfilter-persistent >/dev/null && netfilter-persistent save || true
fi
cd "$DIR" && docker compose up -d
for _ in $(seq 1 20); do [ -s "$DIR/data/id_ed25519.pub" ] && break; sleep 1; done
IP=$(curl -fsS https://api.ipify.org || hostname -I | awk '{print $1}')
echo
echo "=========================================================="
echo " RustDesk server is up"
echo " ID server   : $IP"
echo " Relay server: $IP"
echo " Key         : $(cat "$DIR/data/id_ed25519.pub" 2>/dev/null || echo '(not written yet: docker logs hbbs)')"
echo " Put these three in every RustDesk app: Settings → Network → ID/Relay server"
echo "=========================================================="
