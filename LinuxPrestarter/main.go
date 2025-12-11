package main

import (
	"archive/tar"
	"compress/gzip"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"runtime"
	"path/filepath"
	"strings"
)

// These variables are set at compile time using -ldflags
var (
	ProjectName           string
	JavaDownloadURLAmd64  string
	JavaDownloadURLArm64  string
	LauncherDownloadURL   string
)

const (
	JavaDirectory   = "java"
	LauncherJarFile = "Launcher.jar"
)

func main() {
	log.Println("Starting launcher prestarter...")

	if ProjectName == "" || JavaDownloadURLAmd64 == "" || LauncherDownloadURL == "" {
		log.Fatalf("FATAL: Prestarter is not configured. Please build it from LaunchServer.")
	}

	// 1. Determine architecture and select Java URL
	javaURL := getJavaURL()
	if javaURL == "" {
		log.Fatalf("Unsupported architecture: %s", runtime.GOARCH)
	}

	// 2. Download and unpack Java if not exists
	javaExecutable := findJavaExecutable(JavaDirectory)
	if javaExecutable == "" {
		log.Println("Java runtime not found. Downloading...")
		javaArchive := "java.tar.gz"
		if err := downloadFile(javaURL, javaArchive); err != nil {
			log.Fatalf("Failed to download Java: %v", err)
		}
		log.Println("Unpacking Java runtime...")
		if err := unpackTarGz(javaArchive, JavaDirectory); err != nil {
			log.Fatalf("Failed to unpack Java: %v", err)
		}
		os.Remove(javaArchive) // Clean up the archive

		javaExecutable = findJavaExecutable(JavaDirectory)
		if javaExecutable == "" {
			log.Fatalf("Could not find java executable after unpacking")
		}
	} else {
		log.Println("Java runtime found.")
	}

	// 3. Download Launcher.jar
	log.Printf("Downloading %s...", LauncherJarFile)
	if err := downloadFile(LauncherDownloadURL, LauncherJarFile); err != nil {
		log.Fatalf("Failed to download %s: %v", LauncherJarFile, err)
	}

	// 4. Run the launcher
	log.Printf("Starting launcher with command: %s -jar %s", javaExecutable, LauncherJarFile)

	cmd := exec.Command(javaExecutable, "-jar", LauncherJarFile)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		log.Fatalf("Failed to start launcher: %v", err)
	}
}

func getJavaURL() string {
	switch runtime.GOARCH {
	case "amd64":
		return JavaDownloadURLAmd64
	case "arm64":
		if JavaDownloadURLArm64 != "" {
			return JavaDownloadURLArm64
		}
		return JavaDownloadURLAmd64 // Fallback to amd64 if arm64 URL not provided
	default:
		return ""
	}
}

func downloadFile(url, filepath string) error {
	resp, err := http.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("bad status downloading %s: %s", url, resp.Status)
	}

	out, err := os.Create(filepath)
	if err != nil {
		return err
	}
	defer out.Close()

	_, err = io.Copy(out, resp.Body)
	return err
}

func unpackTarGz(source, destination string) error {
	if _, err := os.Stat(destination); os.IsNotExist(err) {
		os.MkdirAll(destination, 0755)
	}

	reader, err := os.Open(source)
	if err != nil {
		return err
	}
	defer reader.Close()

	gzr, err := gzip.NewReader(reader)
	if err != nil {
		return err
	}
	defer gzr.Close()

	tr := tar.NewReader(gzr)

	for {
		header, err := tr.Next()
		switch {
		case err == io.EOF:
			return nil
		case err != nil:
			return err
		case header == nil:
			continue
		}

        parts := strings.Split(header.Name, "/")
        targetPath := ""
        if len(parts) > 1 {
            targetPath = filepath.Join(parts[1:]...)
        } else {
            continue
        }

		target := filepath.Join(destination, targetPath)

		switch header.Typeflag {
		case tar.TypeDir:
			if _, err := os.Stat(target); err != nil {
				if err := os.MkdirAll(target, 0755); err != nil {
					return err
				}
			}
		case tar.TypeReg:
			// Ensure parent directory exists
			parentDir := filepath.Dir(target)
			if _, err := os.Stat(parentDir); os.IsNotExist(err) {
				if err := os.MkdirAll(parentDir, 0755); err != nil {
					return err
				}
			}

			f, err := os.OpenFile(target, os.O_CREATE|os.O_RDWR, os.FileMode(header.Mode))
			if err != nil {
				return err
			}
			if _, err := io.Copy(f, tr); err != nil {
				f.Close()
				return err
			}
			f.Close()
		}
	}
}

func findJavaExecutable(searchDir string) string {
	var javaPath string
	err := filepath.Walk(searchDir, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if !info.IsDir() && info.Name() == "java" {
			if info.Mode()&0111 != 0 {
				javaPath = path
				return io.EOF // Stop searching
			}
		}
		return nil
	})
	if err != nil && err != io.EOF {
		log.Printf("Error while searching for java executable: %v", err)
		return ""
	}
	return javaPath
}
