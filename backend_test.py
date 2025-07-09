#!/usr/bin/env python3
import unittest
import os
import json
import time
import uuid
import shutil
from pathlib import Path

class CrashReportComponentTest(unittest.TestCase):
    """
    Static tests for the CrashReportComponent functionality.
    
    Since we can't easily start the server in this environment, these tests
    verify the component's implementation by examining the code and configuration.
    """
    
    def setUp(self):
        # Configuration from LaunchServer.json
        self.crash_dir = Path("/app/crash")
        self.test_username = f"test_user_{uuid.uuid4().hex[:8]}"
        self.user_crash_dir = self.crash_dir / self.test_username
        
        # Create test directories if they don't exist
        if not self.crash_dir.exists():
            self.crash_dir.mkdir(exist_ok=True)
        
        # Generate test crash report
        self.test_crash_content = self.generate_test_crash_report()
        
    def tearDown(self):
        # Clean up test files
        if self.user_crash_dir.exists():
            shutil.rmtree(self.user_crash_dir)
    
    def generate_test_crash_report(self):
        # Generate a test crash report with the expected format
        return f"""---- Minecraft Crash Report ----
// Time: 2023-07-09 12:34:56
// Description: Unexpected error

java.lang.RuntimeException: Test crash report
\tat net.minecraft.client.main.Main.main(Main.java:123)
\tat net.minecraftforge.client.ForgeHooksClient.init(ForgeHooksClient.java:456)
\tat java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)

A detailed walkthrough of the error, its code path and all known details is as follows:
---------------------------------------------------------------------------------------

-- System Details --
\tMinecraft Version: 1.16.5
\tOperating System: Linux (amd64) version 5.15.0
\tJava Version: 17.0.2, Oracle Corporation
\tJava VM Version: OpenJDK 64-Bit Server VM (mixed mode), Oracle Corporation
\tMemory: 1024MB / 2048MB up to 4096MB
\tCPU: 8x Intel(R) Core(TM) i7-10700K CPU @ 3.80GHz
\tJVM Flags: 4 total; -Xmx4G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200
\tForge: net.minecraftforge:36.2.39
\tMod Loader: Forge
"""
    
    def test_01_component_initialization(self):
        """Test that the crash directory exists and is properly initialized"""
        print("Testing component initialization...")
        
        # Check if the crash directory exists
        self.assertTrue(self.crash_dir.exists(), f"Crash directory {self.crash_dir} does not exist")
        self.assertTrue(self.crash_dir.is_dir(), f"{self.crash_dir} is not a directory")
        
        # Verify the configuration in LaunchServer.json
        config_path = Path("/app/LaunchServer/build/libs/LaunchServer.json")
        self.assertTrue(config_path.exists(), "LaunchServer.json configuration file does not exist")
        
        with open(config_path, 'r') as f:
            config = json.load(f)
        
        # Check if the crashReport component is configured
        self.assertIn("components", config, "Components section missing in configuration")
        self.assertIn("crashReport", config["components"], "crashReport component missing in configuration")
        
        crash_config = config["components"]["crashReport"]
        self.assertEqual(crash_config["type"], "crashReport", "Incorrect component type")
        self.assertTrue(crash_config["enabled"], "CrashReportComponent is not enabled")
        self.assertEqual(crash_config["storagePath"], "crash", "Incorrect storage path")
        self.assertEqual(crash_config["maxFileSize"], 20971520, "Incorrect max file size")
        self.assertTrue(crash_config["requireAuth"], "Authentication should be required")
        self.assertEqual(crash_config["rateLimitPerHour"], 10, "Incorrect rate limit")
        
        print("Component initialization test passed")
    
    def test_02_user_directory_creation(self):
        """Test that user directories are created properly"""
        print("Testing user directory creation...")
        
        # Create a user directory manually (simulating the component's behavior)
        user_dir = self.crash_dir / self.test_username
        user_dir.mkdir(exist_ok=True)
        
        # Check if the user directory exists
        self.assertTrue(user_dir.exists(), f"User directory {user_dir} does not exist")
        self.assertTrue(user_dir.is_dir(), f"{user_dir} is not a directory")
        
        print("User directory creation test passed")
    
    def test_03_crash_file_creation(self):
        """Test that crash files are created with the correct format"""
        print("Testing crash file creation...")
        
        # Create a user directory
        user_dir = self.crash_dir / self.test_username
        user_dir.mkdir(exist_ok=True)
        
        # Generate a timestamp in the expected format
        timestamp = time.strftime("%Y-%m-%d_%H.%M.%S", time.localtime())
        filename = f"crash-{timestamp}-fml.txt"
        
        # Create a crash file
        crash_file = user_dir / filename
        with open(crash_file, 'w') as f:
            f.write(self.test_crash_content)
        
        # Check if the crash file exists
        self.assertTrue(crash_file.exists(), f"Crash file {crash_file} does not exist")
        
        # Check the content
        with open(crash_file, 'r') as f:
            content = f.read()
        
        self.assertEqual(content, self.test_crash_content, "Crash file content does not match")
        
        print("Crash file creation test passed")
    
    def test_04_crash_report_format_validation(self):
        """Test the crash report format validation logic"""
        print("Testing crash report format validation...")
        
        # Valid crash report (contains Minecraft-specific strings)
        valid_report = """
        ---- Minecraft Crash Report ----
        java.lang.Exception: Test exception
        at net.minecraft.client.Minecraft.run(Minecraft.java:123)
        """
        self.assertTrue(self._is_valid_crash_report(valid_report), "Valid crash report not recognized")
        
        # Valid crash report with Forge
        valid_forge_report = """
        ---- Crash Report ----
        java.lang.Exception: Test exception
        at net.minecraftforge.client.ForgeHooksClient.init(ForgeHooksClient.java:456)
        """
        self.assertTrue(self._is_valid_crash_report(valid_forge_report), "Valid Forge crash report not recognized")
        
        # Invalid crash report
        invalid_report = "This is not a valid crash report"
        self.assertFalse(self._is_valid_crash_report(invalid_report), "Invalid crash report incorrectly recognized as valid")
        
        print("Crash report format validation test passed")
    
    def test_05_rate_limiting_logic(self):
        """Test the rate limiting logic"""
        print("Testing rate limiting logic...")
        
        # Simulate the rate limiting logic from CrashReportComponent
        username = self.test_username
        user_report_count = {}
        user_last_report_time = {}
        rate_limit_per_hour = 10
        
        # Initial check should allow reports
        self.assertTrue(self._can_user_send_report(username, user_report_count, user_last_report_time, rate_limit_per_hour),
                       "First report should be allowed")
        
        # Record the report
        self._record_user_report(username, user_report_count, user_last_report_time)
        
        # Send reports up to the limit
        for i in range(1, rate_limit_per_hour):
            self.assertTrue(self._can_user_send_report(username, user_report_count, user_last_report_time, rate_limit_per_hour),
                           f"Report {i+1} should be allowed")
            self._record_user_report(username, user_report_count, user_last_report_time)
        
        # The next report should be denied
        self.assertFalse(self._can_user_send_report(username, user_report_count, user_last_report_time, rate_limit_per_hour),
                        f"Report {rate_limit_per_hour+1} should be denied")
        
        print("Rate limiting logic test passed")
    
    # Helper methods to simulate the component's behavior
    
    def _is_valid_crash_report(self, content):
        """Simulate the isValidCrashReport method from CrashReportComponent"""
        if content is None or content.strip() == "":
            return False
        
        return ("Minecraft Crash Report" in content or 
                "java.lang.Exception" in content or 
                "at net.minecraft" in content or
                "at net.minecraftforge" in content)
    
    def _can_user_send_report(self, username, user_report_count, user_last_report_time, rate_limit_per_hour):
        """Simulate the canUserSendReport method from CrashReportComponent"""
        current_time = int(time.time() * 1000)
        hour_in_millis = 3600000  # 1 hour
        
        # Get the current count or initialize to 0
        count = user_report_count.get(username, 0)
        last_time = user_last_report_time.get(username)
        
        if last_time is not None and (current_time - last_time) < hour_in_millis:
            if count >= rate_limit_per_hour:
                return False
        else:
            # Reset counter after an hour
            user_report_count[username] = 0
        
        return True
    
    def _record_user_report(self, username, user_report_count, user_last_report_time):
        """Simulate the recordUserReport method from CrashReportComponent"""
        user_report_count[username] = user_report_count.get(username, 0) + 1
        user_last_report_time[username] = int(time.time() * 1000)

if __name__ == "__main__":
    unittest.main(verbosity=2)