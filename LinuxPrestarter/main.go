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
	"path/filepath"
	"runtime"
	"strings"
	"sync"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/app"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/widget"
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

type progressWriter struct {
	total      int64
	written    int64
	progressBar *widget.ProgressBar
	mu         sync.Mutex
}

func (pw *progressWriter) Write(p []byte) (int, error) {
	n := len(p)
	pw.mu.Lock()
	defer pw.mu.Unlock()
	pw.written += int64(n)
	pw.progressBar.SetValue(float64(pw.written) / float64(pw.total))
	return n, nil
}

// GUI represents the graphical user interface
type GUI struct {
	App         fyne.App
	Window      fyne.Window
	ProgressBar *widget.ProgressBar
	StatusLabel *widget.Label
}

// NewGUI creates a new GUI
func NewGUI() *GUI {
	a := app.New()
	w := a.NewWindow(ProjectName)
	w.Resize(fyne.NewSize(400, 100))
	w.SetFixedSize(true)
	w.CenterOnScreen()

	progressBar := widget.NewProgressBar()
	statusLabel := widget.NewLabel("Initializing...")

	w.SetContent(container.NewVBox(
		statusLabel,
		progressBar,
	))

	return &GUI{
		App:         a,
		Window:      w,
		ProgressBar: progressBar,
		StatusLabel: statusLabel,
	}
}
// SetStatus updates the status label text safely
func (g *GUI) SetStatus(text string) {
	g.App.SendNotification(&fyne.Notification{
		Title:   ProjectName,
		Content: text,
	})
	g.StatusLabel.SetText(text)
}

// SetProgress updates the progress bar value safely
func (g *GUI) SetProgress(value float64) {
	g.ProgressBar.SetValue(value)
}


func main() {
	if ProjectName == "" {
		ProjectName = "Launcher" // Default title
	}

	gui := NewGUI()


	go func() {
		defer gui.App.Quit()

		if JavaDownloadURLAmd64 == "" || LauncherDownloadURL == "" || JavaFXDownloadURLAmd64 == "" {
			gui.SetStatus("FATAL: Not configured.")
			log.Println("FATAL: Prestarter is not configured. Please build it from LaunchServer.")
			return
		}

		// 1. Ensure Java is available
		javaURL := getURL(JavaDownloadURLAmd64, JavaDownloadURLArm64)
		if javaURL == "" {
			gui.SetStatus(fmt.Sprintf("Error: Unsupported architecture %s", runtime.GOARCH))
			log.Fatalf("Unsupported architecture: %s", runtime.GOARCH)
		}

		javaExecutable := findJavaExecutable(JavaDirectory)
		if javaExecutable == "" {
			gui.SetStatus("Downloading Java runtime...")
			err := downloadAndUnpackTarGz("Java", javaURL, JavaDirectory, gui)
			if err != nil {
				gui.SetStatus("Error: " + err.Error())
				log.Fatalf("Failed to process Java runtime: %v", err)
			}
			javaExecutable = findJavaExecutable(JavaDirectory)
			if javaExecutable == "" {
				gui.SetStatus("Error: Java not found after unpack.")
				log.Fatalf("Could not find java executable after unpacking")
			}
		} else {
			gui.SetStatus("Java runtime found.")
		}

		// 2. Ensure JavaFX is available
		javafxURL := getURL(JavaFXDownloadURLAmd64, JavaFXDownloadURLArm64)
		if javafxURL == "" {
			gui.SetStatus(fmt.Sprintf("Error: Unsupported architecture for JavaFX %s", runtime.GOARCH))
			log.Fatalf("Unsupported architecture for JavaFX: %s", runtime.GOARCH)
		}

		javafxSDKPath := filepath.Join(JavaFXDirectory, "lib")
		if _, err := os.Stat(javafxSDKPath); os.IsNotExist(err) {
			gui.SetStatus("Downloading JavaFX SDK...")
			err := downloadAndUnpackZip("JavaFX", javafxURL, JavaFXDirectory, gui)
			if err != nil {
				gui.SetStatus("Error: " + err.Error())
				log.Fatalf("Failed to process JavaFX SDK: %v", err)
			}
		} else {
			gui.SetStatus("JavaFX SDK found.")
		}

		// 3. Download Launcher.jar if it doesn't exist
		if _, err := os.Stat(LauncherJarFile); os.IsNotExist(err) {
			gui.SetStatus("Downloading Launcher...")
			err := downloadFileWithProgress(LauncherDownloadURL, LauncherJarFile, gui.ProgressBar)
			if err != nil {
				gui.SetStatus("Error: " + err.Error())
				log.Fatalf("Failed to download %s: %v", LauncherJarFile, err)
			}
		} else {
			gui.SetStatus("Launcher found.")
		}

		// 4. Run the launcher
		gui.SetStatus("Starting Launcher...")
		args := []string{
			"--module-path", javafxSDKPath,
			"--add-modules", JavaFXModules,
			"-jar", LauncherJarFile,
		}
		cmd := exec.Command(javaExecutable, args...)
		if err := cmd.Start(); err != nil { // Use Start instead of Run to detach
			gui.SetStatus("Error: " + err.Error())
			log.Fatalf("Failed to start launcher: %v", err)
		}
		// The prestarter will exit, leaving the launcher running.
	}()

	gui.Window.ShowAndRun()
}

func getURL(amd64URL, arm64URL string) string {
	switch runtime.GOARCH {
	case "amd64":
		return amd64URL
	case "arm64":
		return arm64URL
	default:
		return ""
	}
}
func downloadFileWithProgress(url, filepath string, progressBar *widget.ProgressBar) error {
	progressBar.SetValue(0)
	resp, err := http.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("bad status: %s", resp.Status)
	}

	out, err := os.Create(filepath)
	if err != nil {
		return err
	}
	defer out.Close()

	pw := &progressWriter{
		total:      resp.ContentLength,
		progressBar: progressBar,
	}

	_, err = io.Copy(out, io.TeeReader(resp.Body, pw))
	return err
}

func downloadAndUnpackTarGz(name, url, destination string, gui *GUI) error {
	archivePath := name + ".tar.gz"
	if err := downloadFileWithProgress(url, archivePath, gui.ProgressBar); err != nil {
		return fmt.Errorf("failed to download %s: %w", name, err)
	}
	gui.SetStatus(fmt.Sprintf("Unpacking %s...", name))
	gui.SetProgress(0)
	if err := unpackTarGz(archivePath, destination); err != nil {
		return fmt.Errorf("failed to unpack %s: %w", name, err)
	}
	os.Remove(archivePath)
	return nil
}

func downloadAndUnpackZip(name, url, destination string, gui *GUI) error {
	archivePath := name + ".zip"
	if err := downloadFileWithProgress(url, archivePath, gui.ProgressBar); err != nil {
		return fmt.Errorf("failed to download %s: %w", name, err)
	}
	gui.SetStatus(fmt.Sprintf("Unpacking %s...", name))
	gui.SetProgress(0)
	if err := unpackZip(archivePath, destination); err != nil {
		return fmt.Errorf("failed to unpack %s: %w", name, err)
	}
	os.Remove(archivePath)
	return nil
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
