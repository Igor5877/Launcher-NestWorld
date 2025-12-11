package main

import (
	"archive/tar"
	"archive/zip"
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
	ProjectName            string
	JavaDownloadURLAmd64   string
	JavaDownloadURLArm64   string
	JavaFXDownloadURLAmd64 string
	JavaFXDownloadURLArm64 string
	LauncherDownloadURL    string
)

const (
	JavaDirectory    = "java"
	JavaFXDirectory  = "javafx"
	LauncherJarFile  = "Launcher.jar"
	JavaFXModules    = "javafx.controls,javafx.fxml,javafx.graphics,javafx.media,javafx.swing,javafx.web"
)

func main() {
	log.Println("Starting launcher prestarter...")

	if ProjectName == "" || JavaDownloadURLAmd64 == "" || LauncherDownloadURL == "" || JavaFXDownloadURLAmd64 == "" {
		log.Fatalf("FATAL: Prestarter is not configured. Please build it from LaunchServer.")
	}

	// 1. Ensure Java is available
	javaExecutable := findJavaExecutable(JavaDirectory)
	if javaExecutable == "" {
		log.Println("Java runtime not found. Downloading...")
		if err := downloadAndUnpackTarGz("Java", getURL(JavaDownloadURLAmd64, JavaDownloadURLArm64), JavaDirectory); err != nil {
			log.Fatalf("Failed to process Java runtime: %v", err)
		}
		javaExecutable = findJavaExecutable(JavaDirectory)
		if javaExecutable == "" {
			log.Fatalf("Could not find java executable after unpacking")
		}
	} else {
		log.Println("Java runtime found.")
	}

	// 2. Ensure JavaFX is available
	javafxSDKPath := filepath.Join(JavaFXDirectory, "lib")
	if _, err := os.Stat(javafxSDKPath); os.IsNotExist(err) {
		log.Println("JavaFX SDK not found. Downloading...")
		if err := downloadAndUnpackZip("JavaFX", getURL(JavaFXDownloadURLAmd64, JavaFXDownloadURLArm64), JavaFXDirectory); err != nil {
			log.Fatalf("Failed to process JavaFX SDK: %v", err)
		}
	} else {
		log.Println("JavaFX SDK found.")
	}

	// 3. Download Launcher.jar
	log.Printf("Downloading %s...", LauncherJarFile)
	if err := downloadFile(LauncherDownloadURL, LauncherJarFile); err != nil {
		log.Fatalf("Failed to download %s: %v", LauncherJarFile, err)
	}

	// 4. Run the launcher with JavaFX modules
	log.Printf("Starting launcher with JavaFX...")

	args := []string{
		"--module-path", javafxSDKPath,
		"--add-modules", JavaFXModules,
		"-jar", LauncherJarFile,
	}

	cmd := exec.Command(javaExecutable, args...)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		log.Fatalf("Failed to start launcher: %v", err)
	}
}

func getURL(amd64URL, arm64URL string) string {
	switch runtime.GOARCH {
	case "amd64":
		return amd64URL
	case "arm64":
		if arm64URL != "" {
			return arm64URL
		}
		return amd64URL // Fallback
	default:
		return ""
	}
}

func downloadAndUnpackTarGz(name, url, destination string) error {
	archivePath := name + ".tar.gz"
	if err := downloadFile(url, archivePath); err != nil {
		return fmt.Errorf("failed to download %s: %w", name, err)
	}
	log.Printf("Unpacking %s...", name)
	if err := unpackTarGz(archivePath, destination); err != nil {
		return fmt.Errorf("failed to unpack %s: %w", name, err)
	}
	os.Remove(archivePath)
	return nil
}

func downloadAndUnpackZip(name, url, destination string) error {
	archivePath := name + ".zip"
	if err := downloadFile(url, archivePath); err != nil {
		return fmt.Errorf("failed to download %s: %w", name, err)
	}
	log.Printf("Unpacking %s...", name)
	if err := unpackZip(archivePath, destination); err != nil {
		return fmt.Errorf("failed to unpack %s: %w", name, err)
	}
	os.Remove(archivePath)
	return nil
}

func downloadFile(url, filepath string) error {
	log.Printf("Downloading %s from %s", filepath, url)
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
    // Implementation from previous steps...
	if _, err := os.Stat(destination); os.IsNotExist(err) {
		os.MkdirAll(destination, 0755)
	}
	reader, err := os.Open(source)
	if err != nil { return err }
	defer reader.Close()
	gzr, err := gzip.NewReader(reader)
	if err != nil { return err }
	defer gzr.Close()
	tr := tar.NewReader(gzr)
	for {
		header, err := tr.Next()
		if err == io.EOF { return nil }
		if err != nil { return err }
		if header == nil { continue }
        parts := strings.Split(header.Name, "/")
        targetPath := ""
        if len(parts) > 1 { targetPath = filepath.Join(parts[1:]...)
        } else { continue }
		target := filepath.Join(destination, targetPath)
		switch header.Typeflag {
		case tar.TypeDir:
			if _, err := os.Stat(target); err != nil {
				if err := os.MkdirAll(target, 0755); err != nil { return err }
			}
		case tar.TypeReg:
			parentDir := filepath.Dir(target)
			if _, err := os.Stat(parentDir); os.IsNotExist(err) {
				if err := os.MkdirAll(parentDir, 0755); err != nil { return err }
			}
			f, err := os.OpenFile(target, os.O_CREATE|os.O_RDWR, os.FileMode(header.Mode))
			if err != nil { return err }
			if _, err := io.Copy(f, tr); err != nil { f.Close(); return err }
			f.Close()
		}
	}
}

func unpackZip(source, destination string) error {
    r, err := zip.OpenReader(source)
    if err != nil {
        return err
    }
    defer r.Close()

    if _, err := os.Stat(destination); os.IsNotExist(err) {
        os.MkdirAll(destination, 0755)
    }

    for _, f := range r.File {
        // Trim the top-level directory
        parts := strings.Split(f.Name, "/")
        if len(parts) <= 1 {
            continue // Skip top-level directory itself
        }
        targetPath := filepath.Join(parts[1:]...)
        rc, err := f.Open()
        if err != nil {
            return err
        }
        defer rc.Close()

        path := filepath.Join(destination, targetPath)

        if f.FileInfo().IsDir() {
            os.MkdirAll(path, f.Mode())
        } else {
			if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
				return err
			}
            f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, f.Mode())
            if err != nil {
                return err
            }
            defer f.Close()

            _, err = io.Copy(f, rc)
            if err != nil {
                return err
            }
        }
    }
    return nil
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
