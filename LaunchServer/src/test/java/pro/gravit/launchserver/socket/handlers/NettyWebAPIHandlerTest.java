package pro.gravit.launchserver.socket.handlers;

import com.google.gson.Gson;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture; // Added import
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.util.concurrent.GenericFutureListener; // Added import
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito; // Added import
import org.mockito.junit.jupiter.MockitoExtension;
import pro.gravit.launcher.base.Launcher; // Still needed for Launcher.GsonManager structure
import pro.gravit.launchserver.socket.NettyConnectContext;


import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths; // Added for constructing expected paths

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any; // Added specific static import for any()
import static org.mockito.Mockito.when; // Specific imports for Mockito
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
public class NettyWebAPIHandlerTest {

    @Mock
    private ChannelHandlerContext mockCtx;
    @Mock
    private FullHttpRequest mockHttpRequest;
    @Mock
    private NettyConnectContext mockNettyConnectContext; // Though not used by the static handler
    @Mock
    private HttpHeaders mockHttpHeaders;

    @TempDir
    Path tempDirRoot; // This will be the base for "Crashreport-Client"

    private static final Gson gson = new Gson(); // Used for creating payloads

    // This captures the FullHttpResponse written to the context
    private ArgumentCaptor<FullHttpResponse> responseCaptor;

    @BeforeEach
    void setUp() {
        // Initialize static GsonManager for Launcher
        pro.gravit.launcher.base.Launcher.gsonManager = new pro.gravit.launcher.core.managers.GsonManager();
        pro.gravit.launcher.base.Launcher.gsonManager.initGson();
        assertNotNull(pro.gravit.launcher.base.Launcher.gsonManager.gson, "Launcher.gsonManager.gson should not be null after explicit init in @BeforeEach");

        // Production code writes to Paths.get("Crashreport-Client") relative to CWD.
        // For tests to be clean, we'd ideally redirect this or clean up.
        // We will make test assertions based on this fixed path.
        // The @TempDir is not directly used by the production code path but can be used
        // by tests if they were to mimic the behavior within a controlled directory.
        // However, since production code uses a fixed path, we'll test that behavior.
        // To ensure tests are somewhat isolated, we can delete the "Crashreport-Client" dir before each test.
        Path fixedCrashReportDir = Paths.get("Crashreport-Client");
        if (Files.exists(fixedCrashReportDir)) {
            try {
                Files.walk(fixedCrashReportDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (IOException e) {
                // e.printStackTrace(); // Log or handle cleanup failure if necessary
            }
        }

        responseCaptor = ArgumentCaptor.forClass(FullHttpResponse.class);
        when(mockHttpRequest.headers()).thenReturn(mockHttpHeaders);

        // Configure mockCtx.writeAndFlush behavior
        ChannelFuture mockChannelFuture = mock(ChannelFuture.class);
        Mockito.lenient().when(mockCtx.writeAndFlush(any())).thenReturn(mockChannelFuture);
        Mockito.lenient().when(mockChannelFuture.addListener(any(GenericFutureListener.class))).thenReturn(mockChannelFuture);
    }

    private ByteBuf createJsonPayload(String username, String fileName, String content) {
        NettyWebAPIHandler.CrashReportPayload payload = new NettyWebAPIHandler.CrashReportPayload();
        payload.username = username;
        payload.fileName = fileName;
        payload.content = content;
        String json = gson.toJson(payload);
        return Unpooled.copiedBuffer(json, StandardCharsets.UTF_8);
    }

    // Test Case 1: Valid Request - Report Saved
    @Test
    void testHandleCrashReport_ValidRequest_ReportSaved() throws Exception {
        String username = "testuser";
        String fileName = "crash-2023-01-01.txt";
        String content = "Crash details here";
        ByteBuf payload = createJsonPayload(username, fileName, content);
        when(mockHttpRequest.method()).thenReturn(HttpMethod.POST);
        when(mockHttpRequest.content()).thenReturn(payload);

        // Assume Launcher.gsonManager.gson is a working Gson instance in the production code
        // No need to mock Launcher.getGsonManager() if we don't verify its internal gson instance.
        // The test relies on the production code's Launcher.gsonManager.gson to parse.

        NettyWebAPIHandler.handleCrashReportRequest(mockCtx, mockHttpRequest, mockNettyConnectContext);

        verify(mockCtx).writeAndFlush(responseCaptor.capture());
        FullHttpResponse response = responseCaptor.getValue();
        assertEquals(HttpResponseStatus.OK, response.status());

        // Verify actual file creation and content
        Path expectedUserDir = Paths.get("Crashreport-Client", username);
        Path expectedFilePath = expectedUserDir.resolve(fileName);

        assertTrue(Files.exists(expectedUserDir), "User directory should be created");
        assertTrue(Files.isDirectory(expectedUserDir), "User path should be a directory");
        assertTrue(Files.exists(expectedFilePath), "Crash report file should be created");
        assertEquals(content, Files.readString(expectedFilePath, StandardCharsets.UTF_8), "File content should match");
        // LogHelper.info verification removed
    }

    // Test Case 2: Invalid JSON Payload
    @Test
    void testHandleCrashReport_InvalidJsonPayload() {
        ByteBuf malformedJson = Unpooled.copiedBuffer("{ \"username\": \"testuser\", ", StandardCharsets.UTF_8);
        when(mockHttpRequest.method()).thenReturn(HttpMethod.POST);
        when(mockHttpRequest.content()).thenReturn(malformedJson);

        // Assume Launcher.gsonManager.gson is functional for parsing attempt in production code.
        NettyWebAPIHandler.handleCrashReportRequest(mockCtx, mockHttpRequest, mockNettyConnectContext);

        verify(mockCtx).writeAndFlush(responseCaptor.capture());
        FullHttpResponse response = responseCaptor.getValue();
        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        assertTrue(response.content().toString(StandardCharsets.UTF_8).contains("Invalid JSON format"));
        // LogHelper.warning verification removed
    }

    // Test Case 3: Payload with Missing Fields (username)
    @Test
    void testHandleCrashReport_MissingUsername() {
        ByteBuf payload = createJsonPayload(null, "file.txt", "content");
        when(mockHttpRequest.method()).thenReturn(HttpMethod.POST);
        when(mockHttpRequest.content()).thenReturn(payload);

        NettyWebAPIHandler.handleCrashReportRequest(mockCtx, mockHttpRequest, mockNettyConnectContext);

        verify(mockCtx).writeAndFlush(responseCaptor.capture());
        FullHttpResponse response = responseCaptor.getValue();
        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        assertTrue(response.content().toString(StandardCharsets.UTF_8).contains("Missing fields"));
    }

    // Test Case 3: Payload with Missing Fields (fileName)
    @Test
    void testHandleCrashReport_MissingFileName() {
        ByteBuf payload = createJsonPayload("user1", null, "content");
        when(mockHttpRequest.method()).thenReturn(HttpMethod.POST);
        when(mockHttpRequest.content()).thenReturn(payload);

        NettyWebAPIHandler.handleCrashReportRequest(mockCtx, mockHttpRequest, mockNettyConnectContext);

        verify(mockCtx).writeAndFlush(responseCaptor.capture());
        FullHttpResponse response = responseCaptor.getValue();
        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        assertTrue(response.content().toString(StandardCharsets.UTF_8).contains("Missing fields"));
    }

    // Test Case 4: Invalid HTTP Method
    @Test
    void testHandleCrashReport_InvalidHttpMethod() {
        when(mockHttpRequest.method()).thenReturn(HttpMethod.GET); // Invalid method

        NettyWebAPIHandler.handleCrashReportRequest(mockCtx, mockHttpRequest, mockNettyConnectContext);

        verify(mockCtx).writeAndFlush(responseCaptor.capture());
        FullHttpResponse response = responseCaptor.getValue();
        assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, response.status());
    }

    // Test Case 5: Path Sanitization - Username
    @Test
    void testHandleCrashReport_SanitizeUsername() throws Exception {
        String originalUsername = "../test/user!@#";
        String sanitizedUsername = "testuser"; // Based on current sanitize logic
        String fileName = "report.txt";
        String content = "content for sanitized username";

        ByteBuf payload = createJsonPayload(originalUsername, fileName, content);
        when(mockHttpRequest.method()).thenReturn(HttpMethod.POST);
        when(mockHttpRequest.content()).thenReturn(payload);

        NettyWebAPIHandler.handleCrashReportRequest(mockCtx, mockHttpRequest, mockNettyConnectContext);
            
        verify(mockCtx).writeAndFlush(responseCaptor.capture());
        assertEquals(HttpResponseStatus.OK, responseCaptor.getValue().status());
        
        Path expectedUserDir = Paths.get("Crashreport-Client", sanitizedUsername);
        Path expectedFilePath = expectedUserDir.resolve(fileName);

        assertTrue(Files.exists(expectedUserDir), "User directory for sanitized username should be created");
        assertTrue(Files.isDirectory(expectedUserDir));
        assertTrue(Files.exists(expectedFilePath), "File for sanitized username should be created");
        assertEquals(content, Files.readString(expectedFilePath, StandardCharsets.UTF_8));
    }

    // Test Case 6: Path Sanitization - Filename
    @Test
    void testHandleCrashReport_SanitizeFilename() throws Exception {
        String username = "safeuser";
        String originalFileName = "../../report!@#.txt";
        String sanitizedFileName = "report.txt"; // Based on current sanitize logic
        String content = "content for sanitized filename";

        ByteBuf payload = createJsonPayload(username, originalFileName, content);
        when(mockHttpRequest.method()).thenReturn(HttpMethod.POST);
        when(mockHttpRequest.content()).thenReturn(payload);

        assertNotNull(mockHttpRequest.content(), "mockHttpRequest.content() should not be null before calling handler");
        if (mockHttpRequest.content() != null) {
            assertTrue(mockHttpRequest.content().readableBytes() > 0, "mockHttpRequest.content() should have readable bytes");
        }
            
        NettyWebAPIHandler.handleCrashReportRequest(mockCtx, mockHttpRequest, mockNettyConnectContext);

        verify(mockCtx).writeAndFlush(responseCaptor.capture());
        assertEquals(HttpResponseStatus.OK, responseCaptor.getValue().status());

        Path expectedUserDir = Paths.get("Crashreport-Client", username);
        Path expectedFilePath = expectedUserDir.resolve(sanitizedFileName);

        assertTrue(Files.exists(expectedUserDir));
        assertTrue(Files.isDirectory(expectedUserDir));
        assertTrue(Files.exists(expectedFilePath));
        assertEquals(content, Files.readString(expectedFilePath, StandardCharsets.UTF_8));
    }

    // Test Case 7: Path Sanitization - Empty after Sanitization (username)
    @Test
    void testHandleCrashReport_UsernameEmptyAfterSanitization() {
        ByteBuf payload = createJsonPayload("../../", "report.txt", "content"); // Sanitizes to empty
        when(mockHttpRequest.method()).thenReturn(HttpMethod.POST);
        when(mockHttpRequest.content()).thenReturn(payload);

        NettyWebAPIHandler.handleCrashReportRequest(mockCtx, mockHttpRequest, mockNettyConnectContext);

        verify(mockCtx).writeAndFlush(responseCaptor.capture());
        FullHttpResponse response = responseCaptor.getValue();
        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        assertTrue(response.content().toString(StandardCharsets.UTF_8).contains("Invalid username or fileName after sanitization"));
        // LogHelper.warning verification removed
    }

    // Test Case 7: Path Sanitization - Empty after Sanitization (filename)
    @Test
    void testHandleCrashReport_FilenameEmptyAfterSanitization() {
        ByteBuf payload = createJsonPayload("testuser", "!@#$%^", "content"); // Sanitizes to empty
        when(mockHttpRequest.method()).thenReturn(HttpMethod.POST);
        when(mockHttpRequest.content()).thenReturn(payload);

        NettyWebAPIHandler.handleCrashReportRequest(mockCtx, mockHttpRequest, mockNettyConnectContext);

        verify(mockCtx).writeAndFlush(responseCaptor.capture());
        FullHttpResponse response = responseCaptor.getValue();
        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        assertTrue(response.content().toString(StandardCharsets.UTF_8).contains("Invalid username or fileName after sanitization"));
        // LogHelper.warning verification removed
    }

    // Test Cases for Filesystem Errors (testHandleCrashReport_FilesystemError_*) are removed
    // as they require static mocking of java.nio.file.Files which is not possible with mockito-core alone.
}
