# Inertia — Plan

> Inertia, Minecraft: Java Edition için gelişmiş, profesyonel bir fizik motorudur.

Bu belge, projenin ilk sürümünün (v0.1.0) kapsamını ve kararlarını tanımlar.

---

## 1. Teknoloji

| Konu | Karar |
|---|---|
| Mod platformu | Fabric (Fabric Loader + Fabric API) |
| Minecraft sürümü | 26.3 |
| Java | 25 (26.x gereksinimi, başlarken resmi kaynaktan doğrulanacak) |
| Eşlemeler (mappings) | Mojang isimleri (26.x obfuscate edilmeden yayınlanıyor) |
| Fizik motoru | NVIDIA PhysX 5, [physx-jni](https://github.com/fabmax/physx-jni) üzerinden |
| Sıvı simülasyonu | PhysX PBD parçacık sistemi (GPU / CUDA) |
| Lisans | AGPL-3.0 (PhysX BSD-3 ile uyumlu) |

## 2. Donanım ve platform

- **Yalnızca NVIDIA ekran kartları** (CUDA destekli).
- İşletim sistemi: **Windows x64** ve **Linux x64**.
- CUDA bulunamazsa mod oyunu çökertmez; kendini devre dışı bırakır ve oyuncuya uyarı gösterir.

## 3. v0.1.0 kapsamı

### Olacaklar

1. **CUDA kontrolü ve PhysX başlatma:** Oyun açılırken GPU kontrol edilir, uygunsa PhysX ve GPU sahnesi kurulur.
2. **Özel kova:** Sağ tıklayınca yaklaşık 1 bloğa denk gelen su parçacıkları dünyaya bırakılır.
3. **GPU sıvı simülasyonu:** Akma, sıçrama, birikme, yüzey gerilimi, viskozite.
4. **Bloklarla çarpışma:** Sıvının yakınındaki bloklardan çarpışma şekilleri üretilir; blok kırılıp konunca güncellenir.
5. **Oyuncu etkileşimi:** Sıvı oyuncunun etrafından akar, oyuncuyu iter ve yavaşlatır.
6. **Çizim:** Parçacıklar birleşik, pürüzsüz bir su yüzeyi olarak çizilir (screen-space fluid rendering). Yedek olarak basit parçacık çizimi bulunur.
7. **Ayarlar:** Toplam parçacık limiti, kalite, sıvının silinme mesafesi.
8. **Altyapı:** Gradle projesi, GitHub Actions CI, README, CHANGELOG.

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
- Jar adı: `inertia-<sürüm>+<minecraft sürümü>.jar` (ör. `inertia-0.1.0+26.3.jar`).
- Her sürümün değişiklikleri `CHANGELOG.md` dosyasına yazılır.
