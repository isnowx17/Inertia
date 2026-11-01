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
