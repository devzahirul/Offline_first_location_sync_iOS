import Foundation
import RTLSCore
#if canImport(zlib)
import zlib
#endif

public enum LocationSyncAPIError: Error, Equatable {
    case nonHTTPResponse
    case httpStatus(Int, body: String?)
    case decodingFailed
}

public struct URLSessionLocationSyncAPI: LocationSyncAPI {
    public var baseURL: URL
    public var tokenProvider: AuthTokenProvider
    public var session: URLSession

    public init(
        baseURL: URL,
        tokenProvider: AuthTokenProvider,
        session: URLSession = .shared
    ) {
        self.baseURL = baseURL
        self.tokenProvider = tokenProvider
        self.session = session
    }

    public func upload(batch: LocationUploadBatch) async throws -> LocationUploadResult {
        let url = baseURL
            .appendingPathComponent("v1")
            .appendingPathComponent("locations")
            .appendingPathComponent("batch")

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let token = try await tokenProvider.accessToken()
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        let jsonData = try RTLSJSON.encoder().encode(batch)
        if let compressed = Self.gzipCompress(jsonData), compressed.count < jsonData.count {
            request.httpBody = compressed
            request.setValue("gzip", forHTTPHeaderField: "Content-Encoding")
        } else {
            request.httpBody = jsonData
        }

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw LocationSyncAPIError.nonHTTPResponse
        }
        guard (200..<300).contains(http.statusCode) else {
            throw LocationSyncAPIError.httpStatus(http.statusCode, body: String(data: data, encoding: .utf8))
        }

        do {
            return try RTLSJSON.decoder().decode(LocationUploadResult.self, from: data)
        } catch {
            throw LocationSyncAPIError.decodingFailed
        }
    }

    private static func gzipCompress(_ data: Data) -> Data? {
        guard !data.isEmpty else { return nil }
        var stream = z_stream()
        stream.next_in = UnsafeMutablePointer(mutating: (data as NSData).bytes.bindMemory(to: Bytef.self, capacity: data.count))
        stream.avail_in = uInt(data.count)
        guard deflateInit2_(&stream, Z_DEFAULT_COMPRESSION, Z_DEFLATED, 15 + 16, 8, Z_DEFAULT_STRATEGY, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else { return nil }
        defer { deflateEnd(&stream) }
        var output = Data(count: data.count)
        output.withUnsafeMutableBytes { ptr in
            stream.next_out = ptr.bindMemory(to: Bytef.self).baseAddress
            stream.avail_out = uInt(ptr.count)
            deflate(&stream, Z_FINISH)
        }
        output.count = Int(stream.total_out)
        return output
    }
}

