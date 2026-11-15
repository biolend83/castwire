# Castwire

**Google Cast for Kotlin and Java, without Google's SDK.** Find Chromecasts, Google TVs and TVs with
Cast built in, and play video, music, live streams and local files on them, from Android, Windows,
macOS and Linux. No Play Services, no native code: kotlinx-coroutines, kotlinx-serialization-json and OkHttp.

```kotlin
val castwire = Castwire()                       // on Android: AndroidCastwire.create(context)
val tv = castwire.discover().first()
val session = castwire.connect(tv)
session.load(CastMedia(
    CastSource.Url("https://example.com/movie.mp4", "video/mp4"),
    MediaMetadata(title = "Movie", subtitle = "Studio", imageUrl = "https://example.com/poster.jpg"),
))
session.pause(); session.seek(90_000); session.setVolume(0.4f)
session.close()
```

Castwire is the Cast code of [TubeHaus](https://github.com/biolend83/tubehaus) (its Android and desktop
apps cast YouTube to TVs with it), taken out as a library: the same discovery, connection and relay.

## Why

- **No Play Services.** Works on de-Googled phones, Huawei, F-Droid builds, Fire tablets, and on desktops
  where Google's sender SDK doesn't exist at all.
- **Plays what the TV can't fetch itself.** A TV fetches media on its own: it can't send your app's
  cookies or headers, open files on the phone, use links tied to the phone's IP, or play from servers
  without CORS headers. Castwire's relay serves those to the TV from your device, live HLS playlists
  included.
- **Separate video and audio streams.** Give it the streams (as YouTube and most video sites serve them)
  and it builds the DASH manifest, keeping to codecs every Cast device decodes.
- **Pure JVM.** The same code on Android and desktop, Java 8 bytecode, Android API 24+.

## Install

```kotlin
dependencies {
    implementation("io.github.biolend83:castwire:0.1.0")          // desktop / any JVM
    implementation("io.github.biolend83:castwire-android:0.1.0")  // Android (includes castwire)
}
```

## Discovery

```kotlin
castwire.scan()                                     // background search, about 4 seconds
castwire.devices.collect { list -> showPicker(list) }   // StateFlow, fills in as devices answer
castwire.scanning.collect { busy -> showSpinner(busy) }

val list = castwire.discover(durationMs = 4000)     // or wait for the result
val known = CastDevice.at("192.168.1.50")           // a known address, no discovery
```

`CastDevice` has `name`, `model`, `host`, `hasVideo` (false for speakers) and `isGroup`.

## Playing

```kotlin
val session = castwire.connect(device)              // starts Google's Default Media Receiver
session.load(media, startPositionMs = 30_000, autoplay = true)
```

Sources:

| Source | For |
|---|---|
| `CastSource.Url(url, contentType)` | a public MP4, WebM, MP3, AAC, image |
| `CastSource.Url(url, type, relay = true, headers = ...)` | links that need headers, cookies, or this device's IP |
| `CastSource.LocalFile(file)` | a file on this device (always relayed) |
| `CastSource.Hls(url, relay = ...)` | HLS, live or not; relayed playlists are rewritten |
| `CastSource.DashUrl(url)` | a DASH manifest at a URL |
| `CastSource.Dash(tracks)` | separate video and audio streams, joined into DASH on the device |

```kotlin
val tracks = DashManifest.castable(allTracks, maxHeight = 1080)   // H.264 + AAC where available
session.load(CastMedia(CastSource.Dash(tracks), MediaMetadata("Video"), durationSec = 212.0))
```

Subtitles:

```kotlin
CastMedia(source, metadata, textTracks = listOf(TextTrack(1, "https://example.com/en.vtt", "en", "English")))
session.load(media, activeTrackIds = listOf(1))
session.setActiveTracks(emptyList())               // hide
```

## State and events

```kotlin
session.status.collect { s -> s.playerState; s.durationMs; s.isPlaying }   // StateFlow<MediaStatus>
session.volume.collect { v -> v.level; v.muted }
session.positionMs()                                // the position now, between status reports
session.events.collect { e ->
    when (e) {
        is CastEvent.Disconnected -> {}              // network, TV turned off
        CastEvent.ReceiverStopped -> {}              // the TV went back home, or another phone took over
        CastEvent.Finished -> playNext()
        is CastEvent.PlaybackError -> {}
    }
}
```

Control calls (`play`, `pause`, `seek`, `setPlaybackRate`, `setVolume`, `setMuted`, `stop`) return at once
and can be called from any thread, the main thread included.

## Android

```kotlin
class App : Application() {
    val castwire by lazy { AndroidCastwire.create(this) }
}
```

`castwire-android` brings the permissions and holds the multicast lock during discovery, and a Wi-Fi
lock and a partial wake lock while a session is open (the relay serves the TV from the phone). Keep
your app running in the background while casting the usual way, with a foreground service of type
`mediaPlayback`.

## Jetpack Compose / Compose Multiplatform

Everything is a `StateFlow`:

```kotlin
val devices by castwire.devices.collectAsState()
val status by session.status.collectAsState()
LaunchedEffect(Unit) { castwire.scan() }
```

## Your own HTTP client

The relay fetches with OkHttp. Pass your app's client to share its connection pool, proxy and user agent:

```kotlin
Castwire(CastwireConfig(httpClient = myOkHttpClient))
```

## Custom receivers

```kotlin
val session = castwire.connect(device, appId = "YOUR_APP_ID")
session.send("urn:x-cast:com.example.game", mapOf("move" to "e4"))
session.messages("urn:x-cast:com.example.game").collect { json -> }
```

## Try it from the command line

```
./gradlew :sample:installDist
sample/build/install/sample/bin/sample devices
sample/build/install/sample/bin/sample play "Living room" https://example.com/video.mp4
sample/build/install/sample/bin/sample play 192.168.1.50 ./holiday.mp4
```

## Troubleshooting

- **No devices found:** the phone or computer must be on the same network as the TV, not a guest
  network, and the router must pass multicast (some mesh systems and "AP isolation" settings don't).
  `CastDevice.at(ip)` skips discovery. On Windows, allow Java through the firewall. A TV in standby may not
  answer until it is woken; run the sample with `-v` to see which devices answer.
- **The TV shows an error for relayed media:** the TV must reach this device. VPNs on the phone, or a
  desktop firewall blocking incoming connections, get in the way.

## License

Castwire is dual-licensed:

- **[AGPL-3.0](LICENSE)**, free, for open-source apps whose source is published under the same license;
- a **[commercial license](COMMERCIAL.md)** for closed-source apps, SDKs and services.

Contributions need the agreement in [CONTRIBUTING.md](CONTRIBUTING.md).

Google Cast and Chromecast are trademarks of Google LLC. Castwire is not made by or affiliated with Google.
