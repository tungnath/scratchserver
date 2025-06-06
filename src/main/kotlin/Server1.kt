import java.io.*
import java.net.*
import java.nio.file.*
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.mutableMapOf

class HttpServer(private val port: Int = 8080, private val staticDir: String = "static") {
    private val serverSocket = ServerSocket()
    private val threadPool = Executors.newFixedThreadPool(50)
    private val isRunning = AtomicBoolean(false)
    private val routes = mutableMapOf<Pair<String, String>, (HttpRequest) -> HttpResponse>()

    // MIME type mappings
    private val mimeTypes = mapOf(
        "html" to "text/html",
        "htm" to "text/html",
        "css" to "text/css",
        "js" to "application/javascript",
        "json" to "application/json",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "svg" to "image/svg+xml",
        "ico" to "image/x-icon",
        "txt" to "text/plain",
        "pdf" to "application/pdf"
    )

    init {
        // Create static directory if it doesn't exist
        val staticPath = Paths.get(staticDir)
        if (!Files.exists(staticPath)) {
            Files.createDirectories(staticPath)
            // Create a sample index.html
            createSampleFiles()
        }

        // Add default routes
        setupDefaultRoutes()
    }

    private fun createSampleFiles() {
        val indexHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Kotlin Web Server</title>
                <style>
                    body { font-family: Arial, sans-serif; margin: 40px; }
                    .header { color: #333; border-bottom: 2px solid #007acc; padding-bottom: 10px; }
                    .info { background: #f5f5f5; padding: 15px; border-radius: 5px; margin: 20px 0; }
                </style>
            </head>
            <body>
                <h1 class="header">Kotlin Web Server</h1>
                <div class="info">
                    <p>Server is running successfully!</p>
                    <p>Try these endpoints:</p>
                    <ul>
                        <li><a href="/api/hello">GET /api/hello</a></li>
                        <li><a href="/api/time">GET /api/time</a></li>
                        <li><a href="/api/status">GET /api/status</a></li>
                    </ul>
                </div>
            </body>
            </html>
        """.trimIndent()

        Files.write(Paths.get(staticDir, "index.html"), indexHtml.toByteArray())
    }

    private fun setupDefaultRoutes() {
        // API Routes
        addRoute("GET", "/api/hello") { request ->
            val name = request.queryParams["name"] ?: "World"
            HttpResponse(200, "application/json", """{"message": "Hello, $name!"}""")
        }

        addRoute("GET", "/api/time") { _ ->
            val time = ZonedDateTime.now().format(DateTimeFormatter.ISO_ZONED_DATE_TIME)
            HttpResponse(200, "application/json", """{"timestamp": "$time"}""")
        }

        addRoute("GET", "/api/status") { _ ->
            HttpResponse(
                200, "application/json", """{"status": "running", "port": $port, "threads": ${threadPool.toString()}}"""
            )
        }

        addRoute("POST", "/api/echo") { request ->
            HttpResponse(
                200, "application/json", """{"received": "${request.body}", "method": "${request.method}"}"""
            )
        }
    }

    fun addRoute(method: String, path: String, handler: (HttpRequest) -> HttpResponse) {
        routes[Pair(method.uppercase(), path)] = handler
    }

    fun start() {
        try {
            serverSocket.bind(InetSocketAddress(port))
            serverSocket.reuseAddress = true
            isRunning.set(true)

            println("🚀 Kotlin Web Server started on http://localhost:$port")
            println("📁 Static files served from: $staticDir")
            println("🔧 Thread pool size: 50")

            while (isRunning.get()) {
                try {
                    val clientSocket = serverSocket.accept()
                    threadPool.submit { handleClient(clientSocket) }
                } catch (e: SocketException) {
                    if (isRunning.get()) {
                        println("❌ Socket error: ${e.message}")
                    }
                } catch (e: Exception) {
                    println("❌ Error accepting client: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("❌ Failed to start server: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun handleClient(clientSocket: Socket) {
        try {
            clientSocket.soTimeout = 30000 // 30 second timeout
            clientSocket.tcpNoDelay = true

            val input = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
            val output = BufferedOutputStream(clientSocket.getOutputStream())

            val request = parseRequest(input)
            if (request != null) {
                val response = processRequest(request)
                sendResponse(output, response)
            } else {
                sendResponse(output, HttpResponse(400, "text/plain", "Bad Request"))
            }

            output.flush()

        } catch (e: SocketTimeoutException) {
            println("⏰ Client timeout: ${clientSocket.remoteSocketAddress}")
        } catch (e: Exception) {
            println("❌ Error handling client ${clientSocket.remoteSocketAddress}: ${e.message}")
        } finally {
            try {
                clientSocket.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
        }
    }

    private fun parseRequest(input: BufferedReader): HttpRequest? {
        try {
            val requestLine = input.readLine() ?: return null
            if (requestLine.isEmpty()) return null

            val parts = requestLine.split(" ")
            if (parts.size != 3) return null

            val method = parts[0].uppercase()
            val fullPath = parts[1]
            val version = parts[2]

            // Parse path and query parameters
            val (path, queryParams) = parsePathAndQuery(fullPath)

            // Parse headers
            val headers = mutableMapOf<String, String>()
            var line: String?
            while (input.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                val colonIndex = line!!.indexOf(':')
                if (colonIndex > 0) {
                    val key = line!!.substring(0, colonIndex).trim().lowercase()
                    val value = line!!.substring(colonIndex + 1).trim()
                    headers[key] = value
                }
            }

            // Parse body for POST/PUT requests
            var body = ""
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength > 0 && (method == "POST" || method == "PUT")) {
                val bodyChars = CharArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val read = input.read(bodyChars, totalRead, contentLength - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                body = String(bodyChars, 0, totalRead)
            }

            return HttpRequest(method, path, version, headers, queryParams, body)

        } catch (e: Exception) {
            println("❌ Error parsing request: ${e.message}")
            return null
        }
    }

    private fun parsePathAndQuery(fullPath: String): Pair<String, Map<String, String>> {
        val questionIndex = fullPath.indexOf('?')
        if (questionIndex == -1) {
            return Pair(fullPath, emptyMap())
        }

        val path = fullPath.substring(0, questionIndex)
        val queryString = fullPath.substring(questionIndex + 1)
        val queryParams = mutableMapOf<String, String>()

        queryString.split('&').forEach { param ->
            val equalIndex = param.indexOf('=')
            if (equalIndex > 0) {
                val key = URLDecoder.decode(param.substring(0, equalIndex), "UTF-8")
                val value = URLDecoder.decode(param.substring(equalIndex + 1), "UTF-8")
                queryParams[key] = value
            }
        }

        return Pair(path, queryParams)
    }

    private fun processRequest(request: HttpRequest): HttpResponse {
        return try {
            // Try API routes first
            val routeKey = Pair(request.method, request.path)
            val handler = routes[routeKey]

            if (handler != null) {
                handler(request)
            } else if (request.method == "GET") {
                // Try to serve static file
                serveStaticFile(request.path)
            } else {
                HttpResponse(404, "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            println("❌ Error processing request: ${e.message}")
            HttpResponse(500, "text/plain", "Internal Server Error")
        }
    }

    private fun serveStaticFile(path: String): HttpResponse {
        try {
            val filePath = if (path == "/") {
                Paths.get(staticDir, "index.html")
            } else {
                Paths.get(staticDir, path.removePrefix("/"))
            }

            // Security check - prevent directory traversal
            val normalizedPath = filePath.normalize()
            val staticPath = Paths.get(staticDir).toAbsolutePath().normalize()
            if (!normalizedPath.startsWith(staticPath)) {
                return HttpResponse(403, "text/plain", "Forbidden")
            }

            if (!Files.exists(normalizedPath) || Files.isDirectory(normalizedPath)) {
                return HttpResponse(404, "text/plain", "File Not Found")
            }

            val fileExtension = normalizedPath.toString().substringAfterLast('.', "")
            val mimeType = mimeTypes[fileExtension.lowercase()] ?: "application/octet-stream"
            val content = Files.readAllBytes(normalizedPath)

            return HttpResponse(200, mimeType, content)

        } catch (e: Exception) {
            println("❌ Error serving static file: ${e.message}")
            return HttpResponse(500, "text/plain", "Internal Server Error")
        }
    }

    private fun sendResponse(output: BufferedOutputStream, response: HttpResponse) {
        try {
            val statusLine = "HTTP/1.1 ${response.statusCode} ${getStatusText(response.statusCode)}\r\n"
            output.write(statusLine.toByteArray())

            // Standard headers
            val headers = mutableMapOf<String, String>()
            headers["Content-Type"] = response.contentType
            headers["Content-Length"] = response.contentLength.toString()
            headers["Date"] = ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME)
            headers["Server"] = "Kotlin-WebServer/1.0"
            headers["Connection"] = "close"

            // Add custom headers
            headers.putAll(response.headers)

            // Write headers
            headers.forEach { (key, value) ->
                output.write("$key: $value\r\n".toByteArray())
            }

            output.write("\r\n".toByteArray())

            // Write body
            if (response.body.isNotEmpty()) {
                output.write(response.bodyBytes)
            }

        } catch (e: Exception) {
            println("❌ Error sending response: ${e.message}")
        }
    }

    private fun getStatusText(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        else -> "Unknown"
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket.close()
            threadPool.shutdown()
            if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                threadPool.shutdownNow()
            }
        } catch (e: Exception) {
            println("❌ Error stopping server: ${e.message}")
        }
        println("🛑 Server stopped")
    }
}

// Data classes
data class HttpRequest(
    val method: String,
    val path: String,
    val version: String,
    val headers: Map<String, String>,
    val queryParams: Map<String, String>,
    val body: String
)

data class HttpResponse(
    val statusCode: Int,
    val contentType: String,
    val body: String,
    val headers: MutableMap<String, String> = mutableMapOf()
) {
    constructor(
        statusCode: Int, contentType: String, bodyBytes: ByteArray, headers: MutableMap<String, String> = mutableMapOf()
    ) : this(statusCode, contentType, "", headers) {
        this.bodyBytes = bodyBytes
    }

    var bodyBytes: ByteArray = body.toByteArray()
        private set

    val contentLength: Int
        get() = bodyBytes.size
}

// Main function
fun main() {
    val server = HttpServer(8080, "static")

    // Add shutdown hook
    Runtime.getRuntime().addShutdownHook(Thread {
        println("\n🛑 Shutting down server...")
        server.stop()
    })

    // Add custom routes
    server.addRoute("GET", "/api/info") { request ->
        HttpResponse(
            200, "application/json", """{"server": "Kotlin WebServer", "version": "1.0", "uptime": "running"}"""
        )
    }

    server.addRoute("POST", "/api/data") { request ->
        println("📨 Received POST data: ${request.body}")
        HttpResponse(
            201, "application/json", """{"status": "received", "data": "${request.body}"}"""
        )
    }

    try {
        server.start()
    } catch (e: Exception) {
        println("❌ Server failed to start: ${e.message}")
        e.printStackTrace()
    }
}