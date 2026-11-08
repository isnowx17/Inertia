# Inertia — Plan

> Inertia, Minecraft: Java Edition için gelişmiş, profesyonel bir fizik motorudur.

Bu belge, projenin ilk sürümünün (v0.1.0) kapsamını ve kararlarını tanımlar.

---

## 1. Teknoloji

| Konu | Karar |
|---|---|
| Mod platformu | Fabric (Fabric Loader + Fabric API) |
| Minecraft sürümü | 26.3 |
| Java | 25 (Minecraft 26.3 gereksinimi, doğrulandı) |
| Eşlemeler (mappings) | Mojang isimleri (26.3 obfuscate edilmeden yayınlanıyor, doğrulandı) |
| Fabric | Loader 0.20.1, Fabric API 0.165.0+26.3, Loom 1.19, Gradle 9.8.2 |
| Fizik motoru | NVIDIA PhysX 5.5.1, [physx-jni](https://github.com/fabmax/physx-jni) **2.5.1** üzerinden (CUDA destekli son physx-jni sürümü; 2.6+ CUDA'yı kaldırdı) |
| Sıvı simülasyonu | PhysX PBD parçacık sistemi (GPU / CUDA) |
| Lisans | AGPL-3.0 (PhysX BSD-3 ile uyumlu) |

## 2. Donanım ve platform

- **Yalnızca NVIDIA ekran kartları** (CUDA destekli).
- İşletim sistemi: **Windows x64** ve **Linux x64**.
- CUDA bulunamazsa mod oyunu çökertmez; kendini devre dışı bırakır ve oyuncuya uyarı gösterir.
- **Dağıtım:** her işletim sistemi için ayrı jar (`-windows`, `-linux`). PhysX GPU kütüphanesi platform başına ~250 MB (sıkıştırılmış ~83 MB) olduğu için tek jar yerine bu yol seçildi.
- CUDA'lı native dosyalar Maven Central'da yok; derleme sırasında physx-jni'nin GitHub sürümünden indirilir ve SHA-256 ile doğrulanır.

## 3. v0.1.0 kapsamı

### Olacaklar

1. **CUDA kontrolü ve PhysX başlatma:** Oyun açılırken GPU kontrol edilir, uygunsa PhysX ve GPU sahnesi kurulur.
2. **Özel kova:** Sağ tıklayınca yaklaşık 1 bloğa denk gelen su parçacıkları dünyaya bırakılır.
3. **GPU sıvı simülasyonu:** Akma, sıçrama, birikme, yüzey gerilimi, viskozite.
4. **Bloklarla çarpışma:** Sıvının yakınındaki bloklardan çarpışma şekilleri üretilir; blok kırılıp konunca güncellenir.
5. **Oyuncu etkileşimi:** Sıvı oyuncunun etrafından akar, oyuncuyu iter ve yavaşlatır.
6. **Çizim:** Parçacıklardan arka planda tek parça, pürüzsüz bir su yüzeyi (mesh) üretilir ve çizilir (0.1.1'den itibaren; 0.1.0'da parçacıklar tek tek top olarak çiziliyordu). Fresnel yansıması, biyom su rengi, blok ışığı ve hızlı suda köpük vardır; tek kalan sıçrama parçacıkları küçük damlacık olarak çizilir. Ayardan parçacık görünümüne geçilebilir.
7. **Ayarlar:** Toplam parçacık limiti, kalite, sıvının silinme mesafesi.
8. **Altyapı:** Gradle projesi, GitHub Actions CI, README, CHANGELOG.
9. **Test komutları:** `/inertia info` (GPU ve simülasyon durumu), `/inertia clear` (tüm suyu siler).

### Olmayacaklar

- Multiplayer (mod **yalnızca tek oyunculu** çalışır)
- Kaydetme (dünyadan çıkınca sıvı kaybolur)
- Kovayla sıvıyı geri alma
- Su dışında sıvı türleri
- Vanilla suda değişiklik (göller, okyanuslar, vanilla kova aynen kalır)
- Katı cisimler (rigid body), ragdoll, gazlar

### Beklenti: hacim sınırlı

GPU sıvısında her blok yüzlerce ila binlerce parçacık demektir. Parçacık limitine ve ekran kartına bağlı olarak aynı anda **birkaç düzine ile birkaç yüz blok** arası sıvı simüle edilebilir. Havuz, şelale, kanal gibi kullanımlar hedeflenir; göl doldurmak hedeflenmez.

## 4. Uygulama yaklaşımı

- v0.1.0 **tek seferde** teslim edilir, fazlara bölünmez.
- İlk adımda physx-jni'nin PBD parçacık API'sini tam olarak açıp açmadığı doğrulanır. Açmıyorsa (kendi C++ JNI bağlantısı gerekirse) devam etmeden önce karar istenir.
- Geliştirme ortamında NVIDIA GPU bulunmadığından kod yazılır ve derlenir; oyun içi testler bir NVIDIA bilgisayarda, teslimle birlikte verilecek test listesine göre yapılır.

## 5. Sürümleme

Proje şu an **beta** aşamasındadır. Başlangıç sürümü **`0.1.0`**.

Biçim: `BÜYÜK.ORTA.KÜÇÜK`

| Değişiklik | Artan hane | Örnek |
|---|---|---|
| Hata düzeltme (bugfix) | Son hane | `0.1.0` → `0.1.1` |
| Küçük / orta güncelleme | Orta hane | `0.1.4` → `0.2.0` |
| Büyük güncelleme | İlk hane | `0.7.3` → `1.0.0` |

Kurallar:

- Bir hane artınca **sağındaki haneler sıfırlanır** (`0.1.5` → `0.2.0`, `0.9.3` → `1.0.0`).
- Haneler 9'da başa sarmaz: `0.1.9` → `0.1.10` → `0.1.11`, `1.9.0` → `1.10.0`.
- Sürüm numarası tek bir yerde tutulur: `gradle.properties` (`mod_version`).
- Jar adı: `inertia-<sürüm>+<minecraft sürümü>-<platform>.jar` (ör. `inertia-0.1.0+26.3-windows.jar`).
- Her sürümün değişiklikleri `CHANGELOG.md` dosyasına yazılır.

## 6. Sonraki sürümler

- **0.2.0 (0.3.0 ile yayınlandı):** Daha ince detay (kalite seviyeleri: low / medium / high / ultra, daha ince yüzey ızgarası, çok çekirdekli yüzey hesabı, akışla taşınan yüzey) ve köpük çizgileri (PhysX diffuse parçacıkları: köpük, sıçrama, kabarcık; akış yönünde uzayan köpük izleri).
- **0.3.0 (yayınlandı):** Akan su kovası (sürekli su kaynağı), otomatik büyüyen parçacık tamponu (pratikte sınırsız, `particleLimit`), ekran uzayı yansımaları, kostikler; kendi su shader'ı (kırılma, piksel bazında derinliğe göre renk ve berraklık, dalgacıklar, gökyüzü yansıması, güneş parlaması); basit çizimde de köşe noktası bazında derinlik rengi; varsayılan kalite `high`.
- **0.4.0 (yayınlandı):** Performans (fizik adımı ayrı thread'de, yüzey güncellemesi sınırlı) ve suyun üstüne yatan, akışla uzayan köpük çizgileri; shader'da mermer köpük deseni.
- **0.4.5 (yayınlandı):** Köpük kaldırıldı (shader deseni, hızlı suda beyazlaşma, yüzey köpüğü); sıçrama tanecikleri varsayılan olarak kapalı.
- **0.5.0 (yayınlandı):** Yeni köpük: köpük miktarı → kaplama eğrisi ve kabarcık ağı (foam lace).
- **0.6.0 (yayınlandı):** Tamamen yeni köpük: yüzeyde akıntıyla sürüklenen, girdaplar ve kaynamalarla (boil) çizgi ve halkalara toplanan köpük taneleri; ince köpük haritası (≈4 cm), yakından kabarcık dokusu.
- **0.6.1 (yayınlandı):** Varsayılan kalite `ultra` (eski varsayılan `high` olan ayar dosyaları da `ultra`'ya geçer).
- **1.0.0 (yayınlandı):** Büyük performans güncellemesi: yüzey oluşturma 3-7 kat hızlı (8 düğüme dağıtım + ayrılabilir bulanıklaştırma, derin blokları atlama), su yüzeyi GPU'da kalıcı (kare başına CPU işi yok, köşe shader'ında akışla kaydırma), dışarıdan bakınca iç yüzler çizilmiyor, fizik thread'inde toplu okuma ve tarama, kopyasız dizi paylaşımı.
- **1.1.0 (yayınlandı):** Akan su kovası Minecraft su kovası gibi: kaynak tıklanan yüzün önündeki bloğa konur, o bloğu sürekli suyla dolu tutar, su aşağı (yer yoksa yanlara) akar; kaynağın bloğuna blok koyunca durur.
- **1.2.0 (yayınlandı):** Performans: oyun fizik adımını artık hiç beklemiyor; GPU yetişemediğinde önce daha az alt adıma geçiliyor, sonra yeni su sakin suyun yerini alıyor (kaynak akmaya devam eder, havuz yükselmez; su yavaş çekime düşmez, `keepRealTime`); duran su uyuyor (fizik adımı yok, yeni su, blok değişimi ya da oyuncu yaklaşınca uyanır); sıçrama ve damlacıklar GPU'da (kare başına CPU işi yok), ekran dışı su çizilmiyor, suyun içindeyken derinlik ön-geçişi, ucuzlatılmış yansıma döngüsü, ışık blok başına bir kez, paralel okuma ve tarama.
- **1.2.1 (1.3.0 ile yayınlandı):** Düzeltme: kaynaktan düşen su artık her tick için bir topak halinde kopmuyor; yeni su akışın hemen üstüne, akan suyun hızıyla ekleniyor ve akış hızlandıkça inceliyor. Su ekran kartını zorlarken FPS ekranın yenileme hızıyla sınırlanıyor (sınırsız FPS fizikten ekran kartı zamanı çalıyordu; sınır 90 binden 456 bin parçacığa çıktı). GPU sınırında su artık ince yayıldığı yerlerden silinmiyor: yer yalnızca derin sudan açılıyor, yetmezse kaynak daha ince akıyor.
- **1.3.0:** Durgun su hacim olarak tutuluyor: yüzeyin altındaki sakin su, blok sütunu başına bir yükseklik olarak fizikten çıkarılıyor; yerine üstündeki suyun durduğu bir kutu geliyor, yüzey ağı o kutuyu aynı yoğunlukta suyla dolduruyor (görüntü aynı). Su yeniden hareket edebileceği an (incelince, yanı alçalınca, akış çarpınca, blok değişince, kaynak ya da oyuncu yanında) parçacığa dönüyor. GPU sınırında yavaş derin su da donuyor ve kaynaklar donan kadar yeni su döküyor; su silinmiyor. 7x7 kutu: tek kaynak, yerel Windows 240 FPS gerçek zamanlı 1,3 milyon parçacık; dört kaynak kutuyu 4,6 milyonla dolduruyor (eskiden ~400 binde takılıyordu).
- **1.3.1:** `foamQuality` ayarı: köpüğün ayrıntısı kalite seviyesinden ayrı seçilebiliyor (ör. `custom` kalitede ultra köpük).
- **1.4.0:** Varlıklar suyla etkileşiyor (vagon, tekne, hayvan, canavar, eşya, deneyim, düşen blok, TNT): her biri fizikte oyuncu gibi bir kutu, su etrafından akıyor ve dalga yapıyor; su onları akıntıyla taşıyor, yavaşlatıyor ve batma derinliklerine göre kaldırıyor (tekne yüksekte yüzer, eşya yüzer, hayvan zar zor yüzer, vagon yavaşça batar), düşüşü yumuşatıyor. Kaldırma kuvveti varlığın çevresindeki su yüzeyinden, varlığın o anki yerine göre sunucuda hesaplanıyor (tekne sallanmıyor). Yerel ultra, 7x7 kutu, tekne+vagon+inek+eşya: 240 FPS, gerçek zaman.
- **1.4.1:** Sıçramalar artık bir anlık diken olup kaybolmuyor (yüzeyin akış hızı komşularla yumuşatılıyor); yüzeyden ayrılan ince su küçük beyaz noktalara dönüşmüyor, parçacığın kendi boyutunda ve su renginde damla olarak çiziliyor; `droplets` ayarı damlaları kapatıyor.
- **2.0.0:** Büyük optimizasyon güncellemesi: su yüzeyi ekran kartında, her karede kuruluyor (Inertia'nın kendi CUDA çekirdekleri PhysX'in CUDA bağlamında parçacıkları doğrudan GPU'dan alıp yüzeyi oyunun OpenGL tamponlarına yazıyor; ~1 ms GPU, ~0,5 ms CPU, 140 bin köşe), su iki fizik adımı arasında düzgün ilerliyor (bir tick geriden, iki adım arasında ara değerleme), dalgacıkların basamaklı hareketi, sıçramaların her adımda geri zıplaması, suyun bir anlık kaybolması ve köpüğün bulanıklaşması düzeltildi.
- **2.0.1:** Oyundaki metinler (eşyalar, mesajlar, komut çıktıları) oyun dili ne olursa olsun İngilizce; Türkçe çeviri kaldırıldı.
- **2.1.0:** Su sesleri (Minecraft'ın kendi sesleri): düşen su indiği yerde damlıyor ve şapırdıyor (havadayken değil), hafif çalkalanan su hafifçe şapırdıyor, hızlı akıntı akan su gibi şırıldıyor; oyuncu suya girince sıçrama (dalma sesi sadece gerçek düşüşte), sığ suda yürürken ve yüzerken su sesi; kovalar dökünce ve kaynak açılıp kapanınca ses çıkarıyor. `waterSounds` ayarı.
- **2.2.0:** Su dünya ile birlikte kaydediliyor (her parçacık hızıyla, durgun su, kaynaklar; dünyadan çıkarken, oyun kaydederken ve her 60 saniyede otomatik, arka planda). Uzaktaki su artık silinmiyor: 96 bloktan uzakta olduğu yerde durgun su olarak uyuyor, 192 bloktan ötede simülasyondan çıkıp chunk chunk tutuluyor, oyuncu dönünce geri gelip uyanıyor; hepsi kaydediliyor.
- **2.3.0:** Lav: PhysX lav kovaları; ayrı parçacık sistemi, yüksek viskozite ile kalın ve yavaş akış; kendi ışığıyla parlayan opak görünüm (kalın yerler sarı-turuncu), GPU'da her karede kurulan yüzey; içindeki oyuncu yanar; lav sesleri; dünya ile kaydedilir.
- **2.4.0 (Slime Update):** Yumuşak slime blokları: GPU'da kendi CUDA çekirdeklerimizle sonlu elemanlar (kararlı Neo-Hookean, XPBD, 384 tetrahedron); düşer, titrer, ezilir ve eski şekline döner; üstündekinin ağırlığıyla hafifçe ezilir, üstünde durulur, hızlı inişte zıplatır, üstünde yürümek yapışkan; slime'lar birbirine yapışır; vanilla slime dokusu; dünya ile kaydedilir.
- **Aday:** Suda yüzen cisimler (tekne, eşya) — v1 planında "olmayacaklar" listesindeydi, kullanıcı onayıyla ele alınacak.
