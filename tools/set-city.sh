#!/bin/bash
# The city of the prayer times. Method (Aladhan): 8 Gulf, 16 Dubai, 4 Umm al-Qura, 9 Kuwait, 10 Qatar, 5 Egypt, 3 MWL.
# Usage: tools/set-city.sh <tv-ip> <lat> <lon> <method> "<city name>"
set -e
adb -s "$1:5555" shell am broadcast -n app.drivecast.tv/.CityReceiver --es lat "$2" --es lon "$3" --ei method "$4" --es name "'$5'" | tail -1
