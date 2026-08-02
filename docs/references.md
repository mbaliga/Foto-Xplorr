# Technical references

Primary documentation used for the initialization baseline. These links are references, not blanket endorsements of future dependencies.

## Android storage and media

- Shared media and `MediaStore`: https://developer.android.com/training/data-storage/shared/media
- Photo picker and selected-photo access: https://developer.android.com/training/data-storage/shared/photopicker
- Partial photo/video access: https://developer.android.com/about/versions/14/changes/partial-photo-video-access
- Storage Access Framework: https://developer.android.com/guide/topics/providers/document-provider
- Media management requests: https://developer.android.com/reference/android/provider/MediaStore
- Accessing original media location: https://developer.android.com/training/data-storage/shared/media#media-location-permission
- Platform media formats: https://developer.android.com/media/platform/supported-formats
- `ImageDecoder`: https://developer.android.com/reference/android/graphics/ImageDecoder
- AndroidX `ExifInterface`: https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface

## Sensors and spatial behavior

- Position sensors and rotation vectors: https://developer.android.com/develop/sensors-and-location/sensors/sensors_position
- Sensor coordinate system: https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview
- ARCore Geospatial prerequisites and network/cloud dependency: https://developers.google.com/ar/develop/geospatial

ARCore Geospatial is not part of the offline default architecture because it requires Google Play services, location permissions, a configured Google Cloud project/API, and network access. It may be evaluated later only as an optional adapter.

## Build baseline

- Android Gradle Plugin release notes: https://developer.android.com/build/releases/gradle-plugin
- Jetpack Compose setup and BOM: https://developer.android.com/develop/ui/compose/setup
- Kotlin Compose compiler plugin: https://kotlinlang.org/docs/compose-compiler-migration-guide.html

## Map rendering candidate

- MapLibre Native Android: https://maplibre.org/maplibre-native/android/api/
- MapLibre offline regions: https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.offline/-offline-manager/

The architecture treats the 2D map renderer as replaceable and keeps custom terrain rendering separate. A final dependency choice requires offline behavior, license, accessibility, GPU, package-size, and maintenance evaluation.
