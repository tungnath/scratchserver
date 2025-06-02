package com.ravi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files


data class HttpRequest(val method: String, val path: String, val version: String, val headers: Map<String, String>)


fun parseRequest(requestStr: String): HttpRequest {

    val lines = requestStr.split("\n").map { it.trim() }

    // Sample Request

    // Request received:
    // GET / HTTP/1.1
    // Host: localhost:8080
    // Connection: keep-alive
    // ...


    // parse the request lines
    val requestLines = lines[0].split(" ")
    if (requestLines.size < 3) throw IllegalArgumentException("Invalid request: $requestLines")

    val (method, path, version) = requestLines
    println("Method: $method, Path: $path, Version: $version")

    // parse headers
    val headers = mutableMapOf<String, String>()
    for (line in lines.drop(1)) {
        if (line.isEmpty()) break
        val (key, value) = line.split(":", limit = 2).map { it.trim() }
        headers[key] = value
    }

    return HttpRequest(method, path, version, headers)

}

fun sendResponse(clientSocket: Socket, status: String, contentType: String, body: String) {
    val writer = PrintWriter(clientSocket.getOutputStream())
    writer.println("HTTP/1.1 $status")
    writer.println("Content-Type: $contentType")
    writer.println("Content-Length: ${body.length}")
    writer.println()
    writer.println(body)
    writer.flush()
}

fun sendFileResponse(clientSocket: Socket, file: File) {
    val writer = PrintWriter(clientSocket.getOutputStream(), true)
    val contentType = when (file.extension) {
        "html" -> "text/html"
        "css" -> "text/css"
        "js" -> "application/javascript"
        else -> "text/plain"
    }

    writer.println("HTTP/1.1 200 OK")
    writer.println("Content-Type: $contentType")
    writer.println("Content-Length: ${file.length()}")
    writer.println()
    writer.flush()

    // Stream the file content
    file.inputStream().use { input ->
        clientSocket.getOutputStream().use { output -> input.copyTo(output) }
    }

}

fun serveStaticFiles(path: String): Pair<String, String>? {

    val filepath = "static${path.takeIf { it != "/" } ?: "/index.html"}"
    println("File path: $filepath")

    val file = File(filepath)
    if (!file.exists()) return null

    val content: String = Files.readString(file.toPath())
    val contentType: String = when (file.extension) {
        "html" -> "text/html"
        "css" -> "text/css"
        "js" -> "application/javascript"
        else -> "text/plain"
    }

    println("File and contenttype check $contentType $content")

    return Pair(contentType, content)
}

fun handleApiRequest(path: String): Pair<String, String>? {
    return when (path) {
        "/api/hello" -> Pair("text/plain", "Hello World!")
        "/api/hello-json" -> Pair("application/json", """{"message":"Hello World!"}""")
        else -> {
            println("Invalid API : $path")
            null
        }
    }
}


suspend fun handleClient(clientSocket: Socket) {
    try {
        println("New client connected!")

        // Read from the client socket
        val inputStream = clientSocket.getInputStream()

        if (inputStream.available() <= 0) {
            println("No data available in the input stream!")
            sendResponse(
                clientSocket, "400 Bad Request", "text/plain; charset=utf-8", "No request data received."
            )
            return
        }

        val reader = BufferedReader(InputStreamReader(inputStream))
        val request = StringBuilder()
        var line: String?

        while (reader.readLine().also { line = it } != null) {
            println("each line is $line")
            request.append(line).append("\n")
            if (line!!.isEmpty()) break
        }

        val requestStr = request.toString()
        println("Request content : $requestStr")

        // Parse the HttpRequest
        val parsedHttpRequest = parseRequest(requestStr)
        println("Parsed HTTP request: $parsedHttpRequest")

        // 3. handle API requests
        val response = handleApiRequest(parsedHttpRequest.path)
        if (response == null) {

            // 2. serve static files
            serveStaticFiles(parsedHttpRequest.path) ?: run {
                sendResponse(
                    clientSocket, "404 Not Found", "text/plain; charset=utf-8", "File not found."
                )
//                clientSocket.close()
//                return@run
            }

//            sendResponse(
//                clientSocket, "404 Not Found", "text/plain; charset=utf-8", "File not found."
//            )

        } else {
            val (contentType, contentValue) = response // response here is non null
            sendResponse(clientSocket, "200 OK", contentType, contentValue)
        }


//        // 2. serve static files
//        serveStaticFiles(parsedHttpRequest.path)?:run {
//            sendResponse(
//                clientSocket,
//                "404 Not Found",
//                "text/plain; charset=utf-8",
//                "File not found."
//            )
//            clientSocket.close()
//            return@run
//        }


//        // 1. send basic response
//        sendResponse(
//            clientSocket,
//            "200 OK",
//            "text/plain; charset=utf-8",
//            "Hey, Thank you for calling.\nCan hear you fine and this is your ref no. ${Random.nextInt(19998)}.\n\nThanks."
//        )
//        clientSocket.close()

    } catch (e: Exception) {
        println("error: ${e.localizedMessage}")
    } finally {
        clientSocket.close()
    }

}


fun main() = runBlocking {

    val port = 8080
    val serverSocket = ServerSocket(port)

    println("Server listening on port $port")

    while (true) {
        val clientSocket = serverSocket.accept()

        // launch a coroutine for each client
        launch(Dispatchers.IO) {
            handleClient(clientSocket)
        }

    }


}


