# Payment Observability Lab

Ödeme observability yazısındaki iddiaları kendi makinende ölçmek için kurulmuş bir laboratuvar.

Amaç: tag cardinality patlamasını canlı görmek, gerçek sayıları not almak ve ekran görüntüsünü almak.

## Kurulum

```bash
cd ~/Desktop/payment-observability-lab
chmod +x lab.sh
./lab.sh up
```

İlk sefer Maven build yüzünden ~2 dakika sürer. Sonrasında saniyeler.

Açılan adresler:

| Ne | Nerede |
|---|---|
| Grafana dashboard | http://localhost:3000/d/payment-obs-lab |
| Prometheus | http://localhost:9090 |
| Uygulama metrikleri | http://localhost:8080/actuator/prometheus |

Grafana'da login yok, doğrudan açılır.

---

## Ölçüm protokolü

Sırayı bozma — karşılaştırmanın anlamlı olması buna bağlı.

### 1. Temiz başlangıç

```bash
./lab.sh reset
```

`reset` hem app'i hem Prometheus'u **yok edip yeniden yaratır**. İkisi de gerekli:

- Micrometer registry'si kaydettiği meter'ı asla unutmaz — `bad` modda üretilen binlerce seri `good` moda dönünce de bellekte durur.
- Prometheus'un TSDB'si container'ın yazılabilir katmanında (`/prometheus`). `docker compose restart` bu katmanı **silmez**; önceki koşunun serileri yeni ölçüme sızar. Bunu kurulum sırasında bizzat yaşadım: bad fazından sonra good fazını ölçtüm ve Prometheus hâlâ 76.813 aktif seri gösteriyordu.

`down`/`up` yerine sadece `restart` kullanırsan karşılaştırma sessizce kirlenir ve bunu fark etmen zor olur.

### 2. Sağlıklı tag tasarımı — referans ölçüm

```bash
./lab.sh good
```

**5 dakika bekle.** Sonra:

```bash
./lab.sh snapshot
```

**Not al:** aktif seri sayısı, `payment_authorization_total` seri sayısı, Prometheus RSS, scrape süresi.

Beklenen: authorization counter'ı **240 seri**. 10 acquirer × 3 şema × 2 three_ds × 4 sonuç.

İlk dakikalarda 240'ı değil 225–235 bandını göreceksin. Sebebi hata değil: `TIMEOUT` sonucu %1 olasılıkla üretiliyor, o kombinasyonların hepsinin en az bir kez düşmesi zaman alıyor. Bekledikçe 240'a oturur.

Grafana'yı aç, üstteki iki panelin ekran görüntüsünü al.

### 3. BIN tag'ini aç

```bash
./lab.sh reset
./lab.sh bad 200 2000
```

**5 dakika bekle**, sonra tekrar `./lab.sh snapshot`.

Canlı izlemek istersen ayrı bir terminalde:

```bash
./lab.sh watch
```

Teorik tavan **480.000** seri (240 × 2000). Pratikte oraya varmadan önce üretilen event sayısı sınırlıyor: 200 rps ile sekiz dakikada ~96.000 event atılıyor, dolayısıyla seri sayısı 73 bin civarında oluyor. Yani tavanı görmek için değil, **eğriyi** görmek için koşturuyorsun.

**Dikkat et:** tırmanış anlık değil, kademelidir — yeni BIN'ler trafiğe girdikçe seri sayısı artar. Yazıda "production'a çıkınca başlar" dediğin şey tam olarak bu eğri. Ekran görüntüsünü tırmanış devam ederken al, düzleştikten sonra değil.

Referans olması için `rps=200, binPool=2000` ile ölçtüğüm sonuçlar:

| | Sağlıklı tasarım | BIN tag'i açık (8 dk) | Kat |
|---|---|---|---|
| `payment_authorization_total` seri | 240 | 73.004 | 304× |
| Prometheus toplam aktif seri | 1.299 | 73.403 | 56× |
| Prometheus RSS | 92 MB | 393 MB | 4,2× |
| Scrape süresi | 13 ms | 399 ms | 30× |
| TSDB chunk sayısı | 1.299 | 73.403 | 56× |

Tırmanış eğrisi: +2 dk 22.244 · +4 dk 41.571 · +6 dk 58.399 · +8 dk 73.004

Bad fazını iki kez bağımsız koşturdum, 72.883 ve 73.004 çıktı — sonuç tekrarlanabilir.

### 4. Sınırı zorla (isteğe bağlı)

```bash
./lab.sh reset
./lab.sh bad 200 10000
```

2.4 milyon potansiyel seri. Prometheus'a `mem_limit: 3g` verdim; bu seviyede container'ın OOM ile ölmesi muhtemel. Ölürse `docker compose logs prometheus` çıktısını sakla — yazıda "sonu buraya varıyor" diyebileceğin en net kanıt o.

Mac'ini yormak istemiyorsan bu adımı atla, 2000'lik ölçüm yazı için fazlasıyla yeterli.

### 5. Latency spike — trace/exemplar anlatısı için

```bash
./lab.sh good
./lab.sh spike 150 paybull
```

Spike **tek bir facilitator'ı** yavaşlatır, hepsini değil. Bu bilinçli: yazının argümanı "biri bozulunca toplamda kaybolur" olduğu için grafikte de tek bir çizginin diğerlerinden ayrılması gerekiyor. Dördü birden spike ederse görsel argümanı desteklemek yerine çürütür.

"Facilitator p99 latency" panelinde `paybull` 300 ms'den 5,5 saniyeye çıkarken diğer üçü kıpırdamaz. "Outbox en eski bekleyen kayıt yaşı" panelinde de birikmeyi görürsün.

---

## Yazı için görsel üretme

```bash
./lab.sh shots good          # -> shots/good/*.png
./lab.sh shots bad now-30m   # -> shots/bad/*.png
```

Grafana'nın image-renderer servisi üzerinden her paneli 1200×600 PNG olarak, tüm dashboard'u 1600×1500 olarak basar. Ekran görüntüsü almana gerek yok, tarayıcı bile açman gerekmiyor.

Üretilen dosyalar:

| Dosya | Panel |
|---|---|
| `00-dashboard.png` | Tüm dashboard |
| `01-active-series.png` | Prometheus aktif time series — **yazının ana kanıtı** |
| `02-prometheus-memory.png` | Prometheus RSS |
| `03-scrape-duration.png` | Scrape süresi — ilk belirti |
| `04-series-count.png` | `payment_authorization_total` seri sayısı |
| `05-auth-success-rate.png` | Acquirer bazında başarı oranı |
| `06-3ds-funnel.png` | 3DS funnel |
| `07-facilitator-p99.png` | Facilitator p99 — tek facilitator spike'ı |
| `08-outbox-age.png` | Outbox yaşı |

Medium'a bunları sürükle-bırak ile ekleyeceksin.

---

## Kurarken çıkan bulgu — yazıya girmeli

Lab'ın ilk halinde `bin` tag'i sadece `bad` modda ekleniyordu, `good` modda hiç yoktu. Yani aynı metrik adı (`payment.authorization`) çalışma anında iki farklı tag key seti ile kaydediliyordu.

Sonuç: **deney sessizce çalışmadı.** `bad` moda geçmesine rağmen `bin` tag'i scrape çıktısında hiç görünmedi, seri sayısı 228'de sabit kaldı. Uygulama loglarında tek bir hata, uyarı ya da exception yok. Micrometer'ı TRACE seviyesine çekince de bir şey çıkmadı.

Daha ilginci: davranış tutarlı bile değil. Taze başlatılmış bir JVM'de aynı senaryoyu tekrarlayınca bu sefer **tersi** oldu — tüm çıktı `bin` tag'li geldi ve tag'siz kaydedilmiş meter'ların hepsi çıktıdan kayboldu.

Çıkarılacak ders, tam olarak yazının savunduğu şeyin daha sert bir versiyonu: aynı metrik adı altında tag key setini değiştirmek yalnızca kötü bir fikir değil, **sessizce veri kaybettiren** bir şey. Yanlış tag eklediğinizde en iyi ihtimalle Prometheus'u şişirirsiniz; en kötü ihtimalle metriğinizin bir kısmı hiç görünmeden yok olur ve bunu size kimse söylemez.

> Yazıda bu bulguyu aktarırken Micrometer'ın iç mekanizması hakkında kesin konuşma. Elimizdeki kanıt "davranış tutarsız ve sessiz" demeye yetiyor, "şu sınıf şunu yapıyor" demeye yetmiyor. Kesinleştirmek istersen Micrometer'ın `PrometheusMeterRegistry` kaynağına bakman gerekir.

Lab'ın şu anki halinde `bin` tag key'i her iki modda da var — `good` modda sabit `none` değerini alıyor. Böylece key seti hiç değişmiyor ve ölçülen şey sadece değer çeşitliliği oluyor.

## Neler var içinde

```
app/                    Spring Boot 3.3 / Java 17 / Micrometer
  PaymentMetrics.java   İyi ve kötü tag tasarımı yan yana
  LoadGenerator.java    Arka planda sentetik ödeme trafiği
  LoadController.java   /load/start, /load/stop, /load/spike
prometheus/             5 saniyelik scrape, kalıcı volume yok
grafana/                Datasource + dashboard provisioning + image renderer
shots/                  Üretilen panel PNG'leri (good/ ve bad/)
lab.sh                  Tüm komutlar
```

Üretilen metrikler:

| Metrik | Tip | Ne için |
|---|---|---|
| `payment_authorization_total` | Counter | Success rate, cardinality deneyi |
| `payment_facilitator_call_seconds` | Timer + histogram | p99 latency, spike |
| `payment_threeds_stage_total` | Counter | 3DS funnel drop-off |
| `outbox_oldest_pending_age_seconds` | Gauge | Tutarlılık katmanı |

## Kapatma

```bash
./lab.sh down
```

Named volume kullanmadığım için arkada veri kalmaz.
