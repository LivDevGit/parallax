#!/usr/bin/env bash
# Usage: bench.sh <package> <component> <label>
S=${S:?set S to the TV adb address, e.g. S=192.168.1.20:5555}
PKG=$1; COMP=$2; LABEL=$3; RUNS=${RUNS:-7}
a() { adb -s $S shell "$@" | tr -d '\r'; }

echo "== $LABEL ($PKG)"
echo "-- cold start (am start -S -W), $RUNS runs"
for i in $(seq $RUNS); do
  a am force-stop $PKG; sleep 3
  a am start -S -W -n $COMP | grep -E 'TotalTime|WaitTime' | tr '\n' ' '; echo
  sleep 6
done

echo "-- warm start (launcher in background, return from Settings), $RUNS runs"
for i in $(seq $RUNS); do
  a am start -n com.android.tv.settings/.MainSettings >/dev/null; sleep 4
  a am start -W -n $COMP | grep -E 'TotalTime|WaitTime' | tr '\n' ' '; echo
  sleep 4
done

sleep 15
echo "-- memory after 15s idle"
a dumpsys meminfo $PKG | grep -E 'TOTAL PSS|TOTAL RSS|Java Heap|Native Heap|Graphics' | head -6

echo "-- cpu idle (top, 5 x 2s samples)"
PID=$(a pidof $PKG)
a top -b -n 5 -d 2 -p $PID | grep -E "^ *$PID " | awk '{print $9}' | tr '\n' ' '; echo

echo "-- D-pad scroll: 30 x RIGHT then 30 x LEFT at 250 ms"
a dumpsys gfxinfo $PKG reset >/dev/null
for i in $(seq 30); do a input keyevent 22; sleep 0.25; done
for i in $(seq 30); do a input keyevent 21; sleep 0.25; done
sleep 2
a dumpsys gfxinfo $PKG | grep -E 'Total frames rendered|Janky frames|50th|90th|95th|99th|Number Missed Vsync|Number Slow UI|Number Frame deadline' | head -12
echo
