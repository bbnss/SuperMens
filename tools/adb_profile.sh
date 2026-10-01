#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Uso: tools/adb_profile.sh [--name NOME] [--duration SECONDI]
Campiona ogni 5 secondi fino a Ctrl+C o alla durata richiesta.
Produce un CSV in tools/profiles/ e un riepilogo sul terminale.
EOF
}

trial="prova"
duration=0
while (($#)); do
  case "$1" in
    --name) [[ $# -ge 2 ]] || { usage; exit 2; }; trial="$2"; shift 2 ;;
    --duration) [[ $# -ge 2 ]] || { usage; exit 2; }; duration="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage; exit 2 ;;
  esac
done
[[ "$duration" =~ ^[0-9]+$ ]] || { echo "Durata non valida" >&2; exit 2; }
trial="$(printf '%s' "$trial" | tr -cs 'A-Za-z0-9_-' '_' | cut -c1-50)"
command -v adb >/dev/null || { echo "adb non trovato" >&2; exit 1; }
mapfile_devices="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
device_count="$(printf '%s\n' "$mapfile_devices" | awk 'NF {n++} END {print n+0}')"
[[ "$device_count" == 1 ]] || { echo "Collega esattamente un dispositivo ADB autorizzato (trovati: $device_count)" >&2; exit 1; }
serial="$mapfile_devices"
package="it.supermens.offline"
output_dir="$(cd "$(dirname "$0")" && pwd)/profiles"
mkdir -p "$output_dir"
csv="$output_dir/${trial}-$(date -u +%Y%m%dT%H%M%SZ).csv"
printf 'timestamp_utc,elapsed_s,serial,pid,cpu_percent,pss_kb,battery_percent,battery_temp_c,charging,current_ua\n' > "$csv"
started="$(date +%s)"
finished=0

summary() {
  ((finished == 0)) || return
  finished=1
  echo "CSV: $csv"
  awk -F, 'NR>1 {n++; if($5!="") {cpu+=$5; nc++} if($6!="") {pss+=$6; np++} if($7!="") {battery_start=(n==1?$7:battery_start); battery_end=$7} if($8!="") {temp+=$8; nt++; if($8>peak) peak=$8} if($10!="") {current+=$10; ni++}} END {printf "Campioni: %d | CPU media: %s%% | PSS medio: %s MiB | Batteria: %s%% → %s%% | Temperatura media/max: %s/%s °C | Corrente media: %s µA\n",n,nc?sprintf("%.1f",cpu/nc):"n.d.",np?sprintf("%.1f",pss/np/1024):"n.d.",battery_start?battery_start:"n.d.",battery_end?battery_end:"n.d.",nt?sprintf("%.1f",temp/nt):"n.d.",nt?sprintf("%.1f",peak):"n.d.",ni?sprintf("%.0f",current/ni):"n.d."}' "$csv"
}
trap 'summary' EXIT
trap 'exit 0' INT TERM

echo "Profilo $trial sul dispositivo $serial. Ctrl+C per terminare."
while :; do
  now="$(date +%s)"
  elapsed=$((now-started))
  if ((duration > 0 && elapsed >= duration)); then break; fi
  timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  pid="$(adb -s "$serial" shell pidof "$package" 2>/dev/null | tr -d '\r' | awk '{print $1}' || true)"
  cpu=""
  pss=""
  if [[ -n "$pid" ]]; then
    cpu="$(adb -s "$serial" shell dumpsys cpuinfo 2>/dev/null | tr -d '\r' | awk -v pkg="$package" 'index($0,pkg)>0 && $1 ~ /%/ {gsub(/%/,"",$1); print $1; exit}' || true)"
    pss="$(adb -s "$serial" shell dumpsys meminfo "$package" 2>/dev/null | tr -d '\r' | awk '/TOTAL PSS:/ {gsub(/,/,"",$3); print $3; exit}' || true)"
  fi
  battery="$(adb -s "$serial" shell dumpsys battery 2>/dev/null | tr -d '\r' || true)"
  level="$(printf '%s\n' "$battery" | awk '/^[[:space:]]*level:/ {print $2; exit}')"
  temp_tenths="$(printf '%s\n' "$battery" | awk '/^[[:space:]]*temperature:/ {print $2; exit}')"
  status="$(printf '%s\n' "$battery" | awk '/^[[:space:]]*status:/ {print $2; exit}')"
  charging="no"
  [[ "$status" == 2 || "$status" == 5 ]] && charging="yes"
  temp="$(awk -v t="$temp_tenths" 'BEGIN {if(t!="") printf "%.1f",t/10}')"
  current="$(adb -s "$serial" shell cat /sys/class/power_supply/battery/current_now 2>/dev/null | tr -dc '0-9-' || true)"
  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' "$timestamp" "$elapsed" "$serial" "$pid" "$cpu" "$pss" "$level" "$temp" "$charging" "$current" >> "$csv"
  sleep 5
done
