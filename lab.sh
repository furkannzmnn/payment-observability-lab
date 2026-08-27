#!/usr/bin/env bash
set -euo pipefail

APP="http://localhost:8080"
PROM="http://localhost:9090"

fmt() { if command -v jq >/dev/null 2>&1; then jq .; else cat; echo; fi; }

# Prometheus'tan tek bir skaler deger cek
q() {
  local query="$1"
  local raw
  raw=$(curl -sG --data-urlencode "query=${query}" "${PROM}/api/v1/query")
  if command -v jq >/dev/null 2>&1; then
    echo "${raw}" | jq -r '.data.result[0].value[1] // "yok"'
  else
    echo "${raw}"
  fi
}

case "${1:-help}" in
  up)
    docker compose up -d --build
    echo
    echo "Grafana    -> http://localhost:3000/d/payment-obs-lab"
    echo "Prometheus -> http://localhost:9090"
    echo "App        -> http://localhost:8080/actuator/prometheus"
    echo
    echo "SIRADAKI ADIM: ./lab.sh good"
    echo "Yuk baslatilmadan dashboard bos gorunur — bu normal."
    echo "Grafana'da zaman araligini 'Last 15 minutes' yap, yoksa"
    echo "yeni kalkan lab'in verisi sag kenarda gorunmez kalir."
    ;;

  down)
    docker compose down
    ;;

  good)
    curl -s -X POST "${APP}/load/start?rps=${2:-200}&mode=good" | fmt
    ;;

  bad)
    curl -s -X POST "${APP}/load/start?rps=${2:-200}&mode=bad&binPool=${3:-2000}" | fmt
    ;;

  spike)
    curl -s -X POST "${APP}/load/spike?seconds=${2:-90}&facilitator=${3:-paybull}" | fmt
    ;;

  stop)
    curl -s -X POST "${APP}/load/stop" | fmt
    ;;

  status)
    curl -s "${APP}/load/status" | fmt
    ;;

  reset)
    # Micrometer registry'si meter'lari asla unutmaz: bad modda uretilen binlerce
    # seri, good moda dondugunde de orada durur. Temiz olcum icin app'i de
    # Prometheus'u da bastan baslatmak sart.
    # DIKKAT: `docker compose restart` YETMEZ. Prometheus'un TSDB'si container'in
    # yazilabilir katmaninda (/prometheus) duruyor ve restart o katmani silmiyor —
    # onceki kosunun 70 bin serisi yeni olcume sizip sonuclari kirletiyor.
    # Container'i gercekten yok edip yeniden yaratmak gerekiyor.
    echo "App ve Prometheus yok edilip yeniden yaratiliyor (tum veri silinir)..."
    docker compose rm -sf app prometheus > /dev/null 2>&1
    docker compose up -d app prometheus > /dev/null 2>&1
    echo -n "app hazir olmasi bekleniyor"
    until curl -sf -o /dev/null "${APP}/actuator/health" 2>/dev/null; do
      echo -n "."
      sleep 1
    done
    echo " tamam"
    ;;

  snapshot)
    echo "--------------------------------------------------------"
    echo " ANLIK DURUM  $(date '+%H:%M:%S')"
    echo "--------------------------------------------------------"
    printf " %-42s %s\n" "Prometheus aktif time series:"     "$(q 'prometheus_tsdb_head_series')"
    printf " %-42s %s\n" "payment_authorization_total seri:" "$(q 'count(payment_authorization_total)')"
    printf " %-42s %s bytes\n" "Prometheus RSS:"             "$(q 'process_resident_memory_bytes{job="prometheus"}')"
    printf " %-42s %s sn\n" "payment-app scrape suresi:"     "$(q 'scrape_duration_seconds{job="payment-app"}')"
    printf " %-42s %s\n" "TSDB chunk sayisi:"                "$(q 'prometheus_tsdb_head_chunks')"
    echo "--------------------------------------------------------"
    ;;

  shots)
    LABEL="${2:-run}"
    FROM="${3:-now-15m}"
    OUT="shots/${LABEL}"
    mkdir -p "$OUT"
    BASE="http://localhost:3000/render"
    SLUG="payment-obs-lab/payment-observability-lab"
    COMMON="orgId=1&from=${FROM}&to=now&tz=Europe%2FIstanbul"

    echo "Panel gorselleri uretiliyor -> ${OUT}/  (aralik: ${FROM})"

    # Once tum dashboard
    curl -s --max-time 90 -o "${OUT}/00-dashboard.png" \
      "${BASE}/d/${SLUG}?${COMMON}&width=1600&height=1500&kiosk"

    for entry in \
      "1:01-active-series" \
      "2:02-prometheus-memory" \
      "3:03-scrape-duration" \
      "4:04-series-count" \
      "5:05-auth-success-rate" \
      "6:06-3ds-funnel" \
      "7:07-facilitator-p99" \
      "8:08-outbox-age"
    do
      id="${entry%%:*}"
      name="${entry##*:}"
      curl -s --max-time 90 -o "${OUT}/${name}.png" \
        "${BASE}/d-solo/${SLUG}?${COMMON}&panelId=${id}&width=1200&height=600"
    done

    echo
    ok=0; bad=0
    for f in "${OUT}"/*.png; do
      size=$(wc -c < "$f" | tr -d ' ')
      # NOT: `head -c4 | grep PNG` kullanma. PNG magic byte'i \x89 ile basliyor ve
      # macOS'ta UTF-8 locale altinda grep bu gecersiz byte dizisinde eslesmiyor.
      if file -b "$f" | grep -q '^PNG image data'; then
        printf "  OK    %-28s %s KB\n" "$(basename "$f")" "$((size / 1024))"
        ok=$((ok + 1))
      else
        printf "  HATA  %-28s -> %s\n" "$(basename "$f")" "$(head -c 120 "$f")"
        bad=$((bad + 1))
      fi
    done
    echo
    echo "  ${ok} gorsel hazir, ${bad} hatali"
    ;;

  watch)
    while true; do
      clear
      "$0" snapshot
      sleep 5
    done
    ;;

  *)
    cat <<'EOF'
Kullanim: ./lab.sh <komut>

  up                      Her seyi ayaga kaldir (ilk sefer build ~2 dk)
  down                    Kapat
  reset                   Prometheus'u sifirla (temiz olcum icin)

  good [rps]              Dusuk cardinality yuk baslat        (varsayilan 200 rps)
  bad  [rps] [binPool]    BIN tag'li yuk baslat               (varsayilan 200 rps / 2000 BIN)
  spike [sn] [facilitator] TEK bir facilitator'i yavaslat  (varsayilan 90 sn / paybull)
  stop                    Yuku durdur
  status                  Yuk durumu

  snapshot                Yazi icin gereken sayilari tek ekranda bas
  watch                   snapshot'i 5 saniyede bir yenile
  shots <etiket> [from]   Panel PNG'lerini uret -> shots/<etiket>/
                          orn: ./lab.sh shots good
                               ./lab.sh shots bad now-30m

Grafana: http://localhost:3000/d/payment-obs-lab
EOF
    ;;
esac
