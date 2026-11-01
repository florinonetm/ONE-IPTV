# ONE™ IPTV - Android Application

ONE™ is a modern, high-performance IPTV & Media Player application built with Kotlin and Jetpack Compose for Android.

## Features

- **Live TV & EPG (Electronic Program Guide)**: Interactive timeline, program progress, category filtering, favorites, and reminders.
- **Movies (VOD)**: Full catalog with poster grid, detailed movie view with cast, synopsis, and direct playback.
- **Series & Episodes**: Season navigation, episode lists with duration and overviews.
- **Player Engine**: Built-in hardware-accelerated media player supporting live streams and VOD with timeline scrubbing, 10s skip, and aspect ratio controls.
- **Xtream Codes & M3U Management**: Support for Xtream Codes API credentials, M3U playlists, and direct HLS/MP4 streams.
- **Profiles**: Multi-user profiles (Main, Adult, Teen, Kids) with avatar customization and PIN protection.
- **Diagnostics & Tools**: In-app stream speed test and cache cleanup.
- **Device Pairing & License**: MAC address identification, device pairing key, and license verification.

## Technology Stack

- **UI Framework**: Jetpack Compose (Material 3)
- **Language**: Kotlin 2.2
- **Build System**: Gradle 9.3.1 (Kotlin DSL) with Version Catalog (`libs.versions.toml`)
- **Architecture**: MVVM with Kotlin Coroutines & StateFlow
- **Image Loading**: Coil 2.7
