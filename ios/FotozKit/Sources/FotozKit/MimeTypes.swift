import Foundation

/// Uniform Type Identifier -> MIME, for what PhotoKit reports (`PHAssetResource.uniformTypeIdentifier`).
/// The Kotlin matcher and format classifier speak MIME, so this is the one translation point.
/// Unknown UTIs fall back to the file extension, then to `application/octet-stream` -- never a guess
/// that would make the classifier claim a format it was not told.
public enum MimeTypes {
    private static let byUTI: [String: String] = [
        "public.jpeg": "image/jpeg",
        "public.png": "image/png",
        "public.heic": "image/heic",
        "public.heif": "image/heif",
        "com.compuserve.gif": "image/gif",
        "public.tiff": "image/tiff",
        "com.microsoft.bmp": "image/bmp",
        "org.webmproject.webp": "image/webp",
        "public.avif": "image/avif",
        "public.jpeg-xl": "image/jxl",
        "com.adobe.raw-image": "image/x-adobe-dng",
        "com.adobe.photoshop-image": "image/vnd.adobe.photoshop",
        "public.svg-image": "image/svg+xml",
        "public.mpeg-4": "video/mp4",
        "com.apple.quicktime-movie": "video/quicktime",
        "public.3gpp": "video/3gpp",
    ]
    private static let byExtension: [String: String] = [
        "jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png", "heic": "image/heic",
        "heif": "image/heif", "gif": "image/gif", "tif": "image/tiff", "tiff": "image/tiff",
        "bmp": "image/bmp", "webp": "image/webp", "avif": "image/avif", "jxl": "image/jxl",
        "dng": "image/x-adobe-dng", "svg": "image/svg+xml", "mp4": "video/mp4", "m4v": "video/mp4",
        "mov": "video/quicktime", "3gp": "video/3gpp",
    ]

    public static func mime(uti: String?, fileName: String) -> String {
        if let uti, let m = byUTI[uti.lowercased()] { return m }
        let ext = (fileName as NSString).pathExtension.lowercased()
        return byExtension[ext] ?? "application/octet-stream"
    }
}
