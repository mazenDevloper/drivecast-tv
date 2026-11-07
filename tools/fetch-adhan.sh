#!/bin/bash
# The adhan you chose (a YouTube link, best from the muezzin's own channel), trimmed and at a gentle loudness,
# put on the TV as Music/adhan.mp3. Without it the adhan still shows its banner, silently.
# Adapted from noor-tv (MIT, majedphotos). Usage: tools/fetch-adhan.sh <tv-ip> <youtube-url>
set -e
[ -n "$2" ] || { echo "usage: $0 <tv-ip> <youtube-url>"; exit 1; }
TMP=$(mktemp -d)
yt-dlp -q -x --audio-format mp3 -o "$TMP/a.%(ext)s" "$2"
ffmpeg -v error -y -i "$TMP/a.mp3" -af "silenceremove=start_periods=1:start_threshold=-45dB,loudnorm=I=-18:TP=-2" \
  -ar 44100 -ac 1 -b:a 96k "$TMP/adhan.mp3"
adb -s "$1:5555" shell mkdir -p /sdcard/Music
adb -s "$1:5555" push "$TMP/adhan.mp3" /sdcard/Music/adhan.mp3 >/dev/null
echo "adhan: $(ffprobe -v error -show_entries format=duration -of csv=p=0 "$TMP/adhan.mp3" | cut -d. -f1) s on the TV"
rm -rf "$TMP"
