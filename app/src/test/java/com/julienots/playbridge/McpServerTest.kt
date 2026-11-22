package com.julienots.playbridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Serveur MCP réel sur un port local, avec des outils factices (pas de WebView). */
class McpServerTest {
    private val host = object : ToolHost {
        override val definitions = BrowserTools.DEFINITIONS
        override suspend fun run(name: String, input: Map<String, Any?>) =
            if (name == "screenshot") ToolOutput("Capture 2x2", "/9j/4AAQSkZJRgABAQ==")
            else ToolOutput("ok $name ${input["text"] ?: ""}")
    }

    private fun post(url: String, body: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json, text/event-stream")
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        return code to text
    }

    @Test
    fun jsonRpcOverHttp() {
        val s = McpServer(host, "test", object : McpServer.Listener {
            override fun onToolCall(name: String, args: JSONObject) {}
            override fun onToolResult(name: String, output: ToolOutput) {}
            override fun onClientConnected(clientName: String) {}
        })
        s.token = "SECRET123"
        s.start(18765)
        val base = "http://127.0.0.1:${s.port}/mcp/SECRET123"
        assertEquals(401, post("http://127.0.0.1:${s.port}/mcp/mauvais", "{}").first)
        val (c1, init) = post(base, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""")
        assertEquals(200, c1)
        assertEquals("2025-06-18", JSONObject(init).getJSONObject("result").getString("protocolVersion"))
        assertEquals(202, post(base, """{"jsonrpc":"2.0","method":"notifications/initialized"}""").first)
        val tools = JSONObject(post(base, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""").second)
        assertEquals(12, tools.getJSONObject("result").getJSONArray("tools").length())
        val call = JSONObject(post(base, """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"screenshot","arguments":{}}}""").second)
        val content = call.getJSONObject("result").getJSONArray("content")
        assertEquals("image", content.getJSONObject(1).getString("type"))
        val wait = System.getenv("MCP_E2E_WAIT")
        if (wait != null) {
            println("SERVER READY $base")
            Thread.sleep(wait.toLong() * 1000)
        }
        s.stop()
    }
}
