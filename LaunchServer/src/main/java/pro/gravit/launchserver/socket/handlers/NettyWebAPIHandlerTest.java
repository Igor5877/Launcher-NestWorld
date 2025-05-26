package pro.gravit.launchserver.socket.handlers;

import com.google.gson.Gson;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.gravit.launcher.base.Launcher;
import pro.gravit.launchserver.socket.NettyConnectContext;
import pro.gravit.utils.helper.LogHelper;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
    Path tempDirRoot;
    private Path crashReportBaseDir;

    private static final Gson gson = new Gson();

    // This captures the FullHttpResponse written to the context
    private ArgumentCaptor<FullHttpResponse> responseCaptor;

    @BeforeEach
    void setUp() {
        crashReportBaseDir = tempDirRoot.resolve("Crashreport-Client");
        // The handler creates "Crashreport-Client" relative to current execution path.
        // To use @TempDir, we'd ideally inject this base path into the handler.
        // Since we can't easily do that for a static method and static field based Paths.get(),
        // we will have to either:
        // 1. Assume "Crashreport-Client" is created in the project root during tests (messy).
        // 2. Use PowerMockito or similar to mock Paths.get() (complex setup).
        // 3. Modify the production code to make the base path configurable (best, but out of scope).

        // For this test, we will mock static Files.createDirectories and Files.writeString
        // and verify their inputs, instead of checking the actual file system for tests
        // that involve file writing. For tests verifying actual file content, this is tricky.
        // The prompt asks to use @TempDir as base for "Crashreport-Client".
        // The static handler uses Paths.get("Crashreport-Client").
        // The easiest way to make them align without PowerMock is to change where the handler writes,
        // or to test file content by mocking Files.writeString and capturing its arguments.

        // Let's ensure any static block in NettyWebAPIHandler that might add other servlets
        // doesn't interfere, though our target method is static.
        // The addUnsafeSeverlet for /crashreport is in a static block.
        // We are calling handleCrashReportRequest directly.

        responseCaptor = ArgumentCaptor.forClass(FullHttpResponse.class);
        when(mockHttpRequest.headers()).thenReturn(mockHttpHeaders); // For content type or other headers if needed
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
        ByteBuf payload = createJsonPayload("testuser", "crash-2023-01-01.txt", "Crash details here");
        when(mockHttpRequest.method()).thenReturn(HttpMethod.POST);
        when(mockHttpRequest.content()).thenReturn(payload);

        try (MockedStatic<Launcher> mockedLauncher = Mockito.mockStatic(Launcher.class);
             MockedStatic<LogHelper> mockedLogHelper = Mockito.mockStatic(LogHelper.class);
             MockedStatic<Files> mockedFiles = Mockito.mockStatic(Files.class)) {

            // Mock Gson
            Launcher.GsonManager mockGsonManager = mock(Launcher.GsonManager.class);
            when(mockGsonManager.gson).thenReturn(gson);
            mockedLauncher.when(Launcher::getGsonManager).thenReturn(mockGsonManager);

            // Mock Files operations
            ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
            ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
            mockedFiles.when(() -> Files.createDirectories(any(Path.class))).thenReturn(null); // Assume success
            mockedFiles.when(() -> Files.writeString(pathCaptor.capture(), contentCaptor.capture(), any(StandardCharsets.class)))
                       .thenReturn(null); // Assume success

            NettyWebAPIHandler.handleCrashReportRequest(mockCtx, mockHttpRequest, mockNettyConnectContext);

            verify(mockCtx).writeAndFlush(responseCaptor.capture());
            FullHttpResponse response = responseCaptor.getValue();
            assertEquals(HttpResponseStatus.OK, response.status());

            // Verify path for Files.writeString
            Path expectedPath = Paths.get("Crashreport-Client").resolve("testuser").resolve("crash-2023-01-01.txt");
            // Path captured by writeString will be relative to execution dir.
            assertEquals(expectedPath.toString(), pathCaptor.getValue().toString());
            assertEquals("Crash details here", contentCaptor.getValue());

            mockedLogHelper.verify(() -> LogHelper.info(contains("Saved crash report"), eq("crash-2023-01-01.txt"), eq("testuser")), times(1));
        }
    }

    // Test Case 2: Invalid JSON Payload
    @Test
    void testHandleCrashReport_InvalidJsonPayload() {
        ByteBuf malformedJson = Unpooled.copiedBuffer("{ \"username\": \"testuser\", ", StandardCharsets.UTF_8);
        when(mockHttpRequest.method()).thenReturn(HttpMethod.POST);
        when(mockHttpRequest.content()).thenReturn(malformedJson);

        try (MockedStatic<Launcher> mockedLauncher = Mockito.mockStatic(Launcher.class);
             MockedStatic<LogHelper> mockedLogHelper = Mockito.mockStatic(LogHelper.class)) {
            Launcher.GsonManager mockGsonManager = mock(Launcher.GsonManager.class);
            when(mockGsonManager.gson).thenReturn(gson); // Use real Gson for parsing attempt
            mockedLauncher.when(Launcher::getGsonManager).thenReturn(mockGsonManager);

            NettyWebAPIHandler.handleCrashReportRequest(mockCtx, mockHttpRequest, mockNettyConnectContext);

            verify(mockCtx).writeAndFlush(responseCaptor.capture());
            FullHttpResponse response = responseCaptor.getValue();
            assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
            assertTrue(response.content().toString(StandardCharsets.UTF_8).contains("Invalid JSON format"));
            mockedLogHelper.verify(() -> LogHelper.warning(contains("Invalid JSON received")));
        }
    }

    // Test Case 3: Payload with Missing Fields (username)
    @Test
    void testHandleCrashReport_MissingUsername() {
        ByteBuf payload = createJsonPayload(null, "file.txt", "content");
        when(mockHttpRequest.method()).thenReturn(HttpMethod.POST);
        when(mockHttpRequest.content()).thenReturn(payload);
         try (MockedStatic<Launcher> mockedLauncher = Mockito.mockStatic(Launcher.class)) {
            Launcher.GsonManager mockGsonManager = mock(Launcher.GsonManager.class);
            when(mockGsonManager.gson).thenReturn(gson);
            mockedLauncher.when(Launcher::getGsonManager).thenReturn(mockGsonManager);

            NettyWebAPIHandler.handleCrashReportRequest(mockCtx, mockHttpRequest, mockNettyConnectContext);

            verify(mockCtx).writeAndFlush(responseCaptor.capture());
            FullHttpResponse response = responseCaptor.getValue();
            assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
            assertTrue(response.content().toString(StandardCharsets.UTF_8).contains("Missing fields"));
        }
    }
    // Test Case 3: Payload with Missing Fields (fileName)
    @Test
    void testHandleCrashReport_MissingFileName() {
        ByteBuf payload = createJsonPayload("user1", null, "content");
        when(mockHttpRequest.method()).thenReturn(HttpMethod.POST);
        when(mockHttpRequest.content()).thenReturn(payload);
         try (MockedStatic<Launcher> mockedLauncher = Mockito.mockStatic(Launcher.class)) {
            Launcher.GsonManager mockGsonManager = mock(Launcher.GsonManager.class);
            when(mockGsonManager.gson).thenReturn(gson);
            mockedLauncher.when(Launcher::getGsonManager).thenReturn(mockGsonManager);

            NettyWebAPIHandler.handleCrashReportRequest(mockCtx, mockHttpRequest, mockNettyConnectContext);

            verify(mockCtx).writeAndFlush(responseCaptor.capture());
            FullHttpResponse response = responseCaptor.getValue();
            assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
            assertTrue(response.content().toString(StandardCharsets.UTF_8).contains("Missing fields"));
        }
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
        ByteBuf payload = createJsonPayload("../test/user!@#", "report.txt", "content");
        when(mockHttpRequest.method()).thenReturn(HttpMethod.POST);
        when(mockHttpRequest.content()).thenReturn(payload);

        try (MockedStatic<Launcher> mockedLauncher = Mockito.mockStatic(Launcher.class);
             MockedStatic<Files> mockedFiles = Mockito.mockStatic(Files.class)) {
            Launcher.GsonManager mockGsonManager = mock(Launcher.GsonManager.class);
            when(mockGsonManager.gson).thenReturn(gson);
            mockedLauncher.when(Launcher::getGsonManager).thenReturn(mockGsonManager);
            
            ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
            mockedFiles.when(() -> Files.createDirectories(any(Path.class))).thenReturn(null);
            mockedFiles.when(() -> Files.writeString(pathCaptor.capture(), anyString(), any(StandardCharsets.class)))
                       .thenReturn(null);

            NettyWebAPIHandler.handleCrashReportRequest(mockCtx, mockHttpRequest, mockNettyConnectContext);
            
            verify(mockCtx).writeAndFlush(responseCaptor.capture());
            assertEquals(HttpResponseStatus.OK, responseCaptor.getValue().status());
            // Username "../test/user!@#" becomes "testuser" after sanitization rule "[^a-zA-Z0-9_.-]" and removal of ".."
            Path expectedDir = Paths.get("Crashreport-Client").resolve("testuser");
            Path writtenFile = pathCaptor.getValue();
            assertTrue(writtenFile.startsWith(expectedDir));
            assertEquals("report.txt", writtenFile.getFileName().toString());
        }
    }

    // Test Case 6: Path Sanitization - Filename
    @Test
    void testHandleCrashReport_SanitizeFilename() throws Exception {
        ByteBuf payload = createJsonPayload("safeuser", "../../report!@#.txt", "content");
        when(mockHttpRequest.method()).thenReturn(HttpMethod.POST);
        when(mockHttpRequest.content()).thenReturn(payload);

        try (MockedStatic<Launcher> mockedLauncher = Mockito.mockStatic(Launcher.class);
             MockedStatic<Files> mockedFiles = Mockito.mockStatic(Files.class)) {
            Launcher.GsonManager mockGsonManager = mock(Launcher.GsonManager.class);
            when(mockGsonManager.gson).thenReturn(gson);
            mockedLauncher.when(Launcher::getGsonManager).thenReturn(mockGsonManager);

            ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
            mockedFiles.when(() -> Files.createDirectories(any(Path.class))).thenReturn(null);
            mockedFiles.when(() -> Files.writeString(pathCaptor.capture(), anyString(), any(StandardCharsets.class)))
                       .thenReturn(null);
            
            NettyWebAPIHandler.handleCrashReportRequest(mockCtx, mockHttpRequest, mockNettyConnectContext);

            verify(mockCtx).writeAndFlush(responseCaptor.capture());
            assertEquals(HttpResponseStatus.OK, responseCaptor.getValue().status());
            // Filename "../../report!@#.txt" becomes "report.txt"
            Path expectedFile = Paths.get("Crashreport-Client").resolve("safeuser").resolve("report.txt");
            assertEquals(expectedFile.toString(), pathCaptor.getValue().toString());
        }
    }

    // Test Case 7: Path Sanitization - Empty after Sanitization (username)
    @Test
    void testHandleCrashReport_UsernameEmptyAfterSanitization() {
        ByteBuf payload = createJsonPayload("../../", "report.txt", "content"); // Sanitizes to empty
        when(mockHttpRequest.method()).thenReturn(HttpMethod.POST);
        when(mockHttpRequest.content()).thenReturn(payload);

        try (MockedStatic<Launcher> mockedLauncher = Mockito.mockStatic(Launcher.class);
             MockedStatic<LogHelper> mockedLogHelper = Mockito.mockStatic(LogHelper.class)) {
            Launcher.GsonManager mockGsonManager = mock(Launcher.GsonManager.class);
            when(mockGsonManager.gson).thenReturn(gson);
            mockedLauncher.when(Launcher::getGsonManager).thenReturn(mockGsonManager);

            NettyWebAPIHandler.handleCrashReportRequest(mockCtx, mockHttpRequest, mockNettyConnectContext);

            verify(mockCtx).writeAndFlush(responseCaptor.capture());
            FullHttpResponse response = responseCaptor.getValue();
            assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
            assertTrue(response.content().toString(StandardCharsets.UTF_8).contains("Invalid username or fileName after sanitization"));
            mockedLogHelper.verify(() -> LogHelper.warning(contains("Invalid or sanitized to empty username or fileName")));
        }
    }
    // Test Case 7: Path Sanitization - Empty after Sanitization (filename)
    @Test
    void testHandleCrashReport_FilenameEmptyAfterSanitization() {
        ByteBuf payload = createJsonPayload("testuser", "!@#$%^", "content"); // Sanitizes to empty or just "." based on rules
        when(mockHttpRequest.method()).thenReturn(HttpMethod.POST);
        when(mockHttpRequest.content()).thenReturn(payload);

        try (MockedStatic<Launcher> mockedLauncher = Mockito.mockStatic(Launcher.class);
             MockedStatic<LogHelper> mockedLogHelper = Mockito.mockStatic(LogHelper.class)) {
            Launcher.GsonManager mockGsonManager = mock(Launcher.GsonManager.class);
            when(mockGsonManager.gson).thenReturn(gson);
            mockedLauncher.when(Launcher::getGsonManager).thenReturn(mockGsonManager);

            NettyWebAPIHandler.handleCrashReportRequest(mockCtx, mockHttpRequest, mockNettyConnectContext);

            verify(mockCtx).writeAndFlush(responseCaptor.capture());
            FullHttpResponse response = responseCaptor.getValue();
            assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
            assertTrue(response.content().toString(StandardCharsets.UTF_8).contains("Invalid username or fileName after sanitization"));
             mockedLogHelper.verify(() -> LogHelper.warning(contains("Invalid or sanitized to empty username or fileName")));
        }
    }


    // Test Case 8: Filesystem Error During Save (createDirectories)
    @Test
    void testHandleCrashReport_FilesystemError_CreateDirectories() throws Exception {
        ByteBuf payload = createJsonPayload("testuser", "report.txt", "content");
        when(mockHttpRequest.method()).thenReturn(HttpMethod.POST);
        when(mockHttpRequest.content()).thenReturn(payload);

        try (MockedStatic<Launcher> mockedLauncher = Mockito.mockStatic(Launcher.class);
             MockedStatic<LogHelper> mockedLogHelper = Mockito.mockStatic(LogHelper.class);
             MockedStatic<Files> mockedFiles = Mockito.mockStatic(Files.class)) {
            
            Launcher.GsonManager mockGsonManager = mock(Launcher.GsonManager.class);
            when(mockGsonManager.gson).thenReturn(gson);
            mockedLauncher.when(Launcher::getGsonManager).thenReturn(mockGsonManager);

            IOException ioException = new IOException("Disk full");
            mockedFiles.when(() -> Files.createDirectories(any(Path.class))).thenThrow(ioException);
            // No need to mock writeString if createDirectories fails first

            NettyWebAPIHandler.handleCrashReportRequest(mockCtx, mockHttpRequest, mockNettyConnectContext);

            verify(mockCtx).writeAndFlush(responseCaptor.capture());
            FullHttpResponse response = responseCaptor.getValue();
            assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.status());
            mockedLogHelper.verify(() -> LogHelper.error(eq(ioException), contains("Error processing /crashreport request")));
        }
    }
    
    // Test Case 8: Filesystem Error During Save (writeString)
    @Test
    void testHandleCrashReport_FilesystemError_WriteString() throws Exception {
        ByteBuf payload = createJsonPayload("testuser", "report.txt", "content");
        when(mockHttpRequest.method()).thenReturn(HttpMethod.POST);
        when(mockHttpRequest.content()).thenReturn(payload);

        try (MockedStatic<Launcher> mockedLauncher = Mockito.mockStatic(Launcher.class);
             MockedStatic<LogHelper> mockedLogHelper = Mockito.mockStatic(LogHelper.class);
             MockedStatic<Files> mockedFiles = Mockito.mockStatic(Files.class)) {
            
            Launcher.GsonManager mockGsonManager = mock(Launcher.GsonManager.class);
            when(mockGsonManager.gson).thenReturn(gson);
            mockedLauncher.when(Launcher::getGsonManager).thenReturn(mockGsonManager);

            mockedFiles.when(() -> Files.createDirectories(any(Path.class))).thenReturn(null); // Assume success
            IOException ioException = new IOException("Permission denied");
            mockedFiles.when(() -> Files.writeString(any(Path.class), anyString(), any(StandardCharsets.class)))
                       .thenThrow(ioException);

            NettyWebAPIHandler.handleCrashReportRequest(mockCtx, mockHttpRequest, mockNettyConnectContext);

            verify(mockCtx).writeAndFlush(responseCaptor.capture());
            FullHttpResponse response = responseCaptor.getValue();
            assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.status());
            mockedLogHelper.verify(() -> LogHelper.error(eq(ioException), contains("Error processing /crashreport request")));
        }
    }
}
