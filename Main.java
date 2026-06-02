package com.javarecord;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.googleapis.media.MediaHttpUploaderProgressListener;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.YouTubeScopes;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoSnippet;
import com.google.api.services.youtube.model.VideoStatus;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.stage.FileChooser;
import javafx.scene.layout.GridPane;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class Main extends Application {

    private static final DateTimeFormatter LOG_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final RecorderService recorderService = new RecorderService();

    private TextField ffmpegPathField;
    private TextField ffmpegInputArgsField;
    private TextField outputFileField;

    private TextField titleField;
    private TextArea descriptionArea;
    private ComboBox<VideoVisibility> visibilityComboBox;
    private ComboBox<CommentSetting> commentSettingComboBox;
    private CheckBox madeForKidsCheckBox;

    private Button startRecordingButton;
    private Button stopRecordingButton;
    private Button uploadButton;
    private Button openLastUploadButton;

    private TextArea logArea;

    private Path lastRecording;
    private String lastUploadedUrl;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("JavaRecord");

        Label title = new Label("JavaRecord");
        title.setStyle("-fx-font-size: 30px; -fx-font-weight: bold;");

        Label subtitle = new Label("Record videos locally, then upload them to YouTube. Default visibility is Unlisted.");
        subtitle.setStyle("-fx-text-fill: #555555;");

        VBox header = new VBox(5, title, subtitle);
        header.setPadding(new Insets(18, 18, 8, 18));

        VBox content = new VBox(14);
        content.setPadding(new Insets(10, 18, 18, 18));
        content.getChildren().addAll(
                createRecordingSection(stage),
                new Separator(),
                createUploadSection(),
                new Separator(),
                createLogSection()
        );

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(scrollPane);

        Scene scene = new Scene(root, 980, 760);
        stage.setScene(scene);

        stage.setOnCloseRequest(event -> {
            if (!recorderService.isRecording()) {
                return;
            }

            Alert alert = new Alert(
                    Alert.AlertType.CONFIRMATION,
                    "JavaRecord is currently recording. Stop recording and exit?",
                    ButtonType.OK,
                    ButtonType.CANCEL
            );

            alert.setHeaderText("Recording in progress");

            if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                event.consume();
                return;
            }

            try {
                recorderService.stop(this::log);
            } catch (Exception exception) {
                log("Could not stop FFmpeg cleanly: " + exception.getMessage());
            }
        });

        stage.show();

        log("JavaRecord started.");
        log("Default YouTube visibility is Unlisted.");
        log("Install FFmpeg first, then make sure `ffmpeg -version` works.");
    }

    private VBox createRecordingSection(Stage stage) {
        Label sectionTitle = new Label("1. Recording");
        sectionTitle.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        ffmpegPathField = new TextField("ffmpeg");
        ffmpegPathField.setPromptText("ffmpeg");

        ffmpegInputArgsField = new TextField(PlatformDefaults.defaultFfmpegInputArgs());
        ffmpegInputArgsField.setPromptText("-f gdigrab -framerate 30 -i desktop");

        outputFileField = new TextField(suggestedOutputPath().toString());

        Button chooseOutputButton = new Button("Choose Output...");
        chooseOutputButton.setOnAction(event -> chooseOutputFile(stage));

        Button checkFfmpegButton = new Button("Check FFmpeg");
        checkFfmpegButton.setOnAction(event -> checkFfmpeg());

        startRecordingButton = new Button("Start Recording");
        startRecordingButton.setDefaultButton(true);
        startRecordingButton.setOnAction(event -> startRecording());

        stopRecordingButton = new Button("Stop Recording");
        stopRecordingButton.setDisable(true);
        stopRecordingButton.setOnAction(event -> stopRecording());

        Button selectExistingVideoButton = new Button("Select Existing Video...");
        selectExistingVideoButton.setOnAction(event -> selectExistingVideo(stage));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setAlignment(Pos.TOP_LEFT);

        addRow(grid, 0, "FFmpeg path", ffmpegPathField, checkFfmpegButton);
        addRow(grid, 1, "FFmpeg input args", ffmpegInputArgsField, null);
        addRow(grid, 2, "Output MP4", outputFileField, chooseOutputButton);

        HBox recordingButtons = new HBox(10, startRecordingButton, stopRecordingButton, selectExistingVideoButton);
        recordingButtons.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label("""
                Windows default records your desktop.
                To record microphone audio too, change FFmpeg input args based on your audio device.
                Example Windows screen-only:
                -f gdigrab -framerate 30 -i desktop
                """);
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #555555;");

        return new VBox(10, sectionTitle, grid, recordingButtons, hint);
    }

    private VBox createUploadSection() {
        Label sectionTitle = new Label("2. YouTube Upload");
        sectionTitle.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        titleField = new TextField();
        titleField.setPromptText("Video title");

        descriptionArea = new TextArea();
        descriptionArea.setPromptText("Video description");
        descriptionArea.setPrefRowCount(5);
        descriptionArea.setWrapText(true);

        visibilityComboBox = new ComboBox<>();
        visibilityComboBox.getItems().setAll(VideoVisibility.values());
        visibilityComboBox.getSelectionModel().select(VideoVisibility.UNLISTED);

        commentSettingComboBox = new ComboBox<>();
        commentSettingComboBox.getItems().setAll(CommentSetting.values());
        commentSettingComboBox.getSelectionModel().select(CommentSetting.ALLOW_ALL);

        madeForKidsCheckBox = new CheckBox("This video is made for kids");

        uploadButton = new Button("Upload to YouTube");
        uploadButton.setOnAction(event -> uploadToYouTube());

        openLastUploadButton = new Button("Open Last Upload");
        openLastUploadButton.setDisable(true);
        openLastUploadButton.setOnAction(event -> {
            if (lastUploadedUrl != null && !lastUploadedUrl.isBlank()) {
                openUrl(lastUploadedUrl);
            }
        });

        Button openStudioButton = new Button("Open YouTube Studio");
        openStudioButton.setOnAction(event -> openUrl("https://studio.youtube.com/"));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        addRow(grid, 0, "Title", titleField, null);
        addRow(grid, 1, "Description", descriptionArea, null);
        addRow(grid, 2, "Visibility", visibilityComboBox, null);
        addRow(grid, 3, "Comment preference", commentSettingComboBox, null);
        addRow(grid, 4, "Audience", madeForKidsCheckBox, null);

        HBox uploadButtons = new HBox(10, uploadButton, openLastUploadButton, openStudioButton);
        uploadButtons.setAlignment(Pos.CENTER_LEFT);

        Label apiNote = new Label("""
                YouTube upload uses OAuth.
                Put client_secret.json in the project root or in ~/.javarecord/client_secret.json.
                Comment preference is shown and logged, but YouTube may require final comment changes in YouTube Studio.
                """);
        apiNote.setWrapText(true);
        apiNote.setStyle("-fx-text-fill: #555555;");

        return new VBox(10, sectionTitle, grid, uploadButtons, apiNote);
    }

    private VBox createLogSection() {
        Label sectionTitle = new Label("3. Activity Log");
        sectionTitle.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefRowCount(12);

        Button clearLogButton = new Button("Clear Log");
        clearLogButton.setOnAction(event -> logArea.clear());

        VBox box = new VBox(8, sectionTitle, logArea, clearLogButton);
        VBox.setVgrow(logArea, Priority.ALWAYS);
        return box;
    }

    private void addRow(GridPane grid, int row, String labelText, javafx.scene.Node field, javafx.scene.Node sideButton) {
        Label label = new Label(labelText + ":");
        label.setMinWidth(145);

        GridPane.setHgrow(field, Priority.ALWAYS);
        grid.add(label, 0, row);
        grid.add(field, 1, row);

        if (sideButton != null) {
            grid.add(sideButton, 2, row);
        }
    }

    private void startRecording() {
        try {
            Path output = resolveOutputPath();
            List<String> inputArgs = CommandLineParser.split(ffmpegInputArgsField.getText());

            recorderService.start(
                    ffmpegPathField.getText().trim(),
                    inputArgs,
                    output,
                    this::log
            );

            lastRecording = output;
            outputFileField.setText(output.toString());

            startRecordingButton.setDisable(true);
            stopRecordingButton.setDisable(false);

            log("Recording started: " + output);
        } catch (Exception exception) {
            showError("Could not start recording", exception);
        }
    }

    private void stopRecording() {
        stopRecordingButton.setDisable(true);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                recorderService.stop(Main.this::log);
                return null;
            }
        };

        task.setOnSucceeded(event -> {
            startRecordingButton.setDisable(false);
            stopRecordingButton.setDisable(true);
            log("Recording stopped.");
            log("Last recording: " + lastRecording);
            outputFileField.setText(suggestedOutputPath().toString());
        });

        task.setOnFailed(event -> {
            startRecordingButton.setDisable(false);
            stopRecordingButton.setDisable(false);
            showError("Could not stop recording", task.getException());
        });

        Thread thread = new Thread(task, "javarecord-stop-recording");
        thread.setDaemon(true);
        thread.start();
    }

    private void uploadToYouTube() {
        Path file = resolveUploadFile();
        if (file == null) {
            return;
        }

        UploadMetadata metadata;

        try {
            metadata = new UploadMetadata(
                    titleField.getText(),
                    descriptionArea.getText(),
                    visibilityComboBox.getValue(),
                    madeForKidsCheckBox.isSelected(),
                    commentSettingComboBox.getValue()
            );
        } catch (Exception exception) {
            showError("Invalid upload metadata", exception);
            return;
        }

        uploadButton.setDisable(true);
        log("Preparing YouTube upload for: " + file);
        log("Visibility: " + metadata.visibility());
        log("Comment preference: " + metadata.commentSetting());

        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return new YouTubeUploader().upload(file, metadata, Main.this::log);
            }
        };

        task.setOnSucceeded(event -> {
            uploadButton.setDisable(false);

            String videoId = task.getValue();
            lastUploadedUrl = "https://youtu.be/" + videoId;

            openLastUploadButton.setDisable(false);

            log("Upload complete.");
            log("YouTube URL: " + lastUploadedUrl);

            openUrl(lastUploadedUrl);
        });

        task.setOnFailed(event -> {
            uploadButton.setDisable(false);
            showError("YouTube upload failed", task.getException());
        });

        Thread thread = new Thread(task, "javarecord-youtube-upload");
        thread.setDaemon(true);
        thread.start();
    }

    private void checkFfmpeg() {
        String ffmpegPath = ffmpegPathField.getText().trim();

        if (ffmpegPath.isBlank()) {
            showError("FFmpeg path is empty", new IllegalArgumentException("Enter ffmpeg or the full path to ffmpeg."));
            return;
        }

        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                Process process = new ProcessBuilder(ffmpegPath, "-version")
                        .redirectErrorStream(true)
                        .start();

                boolean finished = process.waitFor(5, TimeUnit.SECONDS);
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

                if (!finished) {
                    process.destroyForcibly();
                    throw new IllegalStateException("FFmpeg version check timed out.");
                }

                if (process.exitValue() != 0) {
                    throw new IllegalStateException("FFmpeg returned exit code " + process.exitValue() + "\n" + output);
                }

                return output.lines().findFirst().orElse("FFmpeg found.");
            }
        };

        task.setOnSucceeded(event -> log("FFmpeg check OK: " + task.getValue()));
        task.setOnFailed(event -> showError("FFmpeg check failed", task.getException()));

        Thread thread = new Thread(task, "javarecord-check-ffmpeg");
        thread.setDaemon(true);
        thread.start();
    }

    private void chooseOutputFile(Stage stage) {
        Path suggested = suggestedOutputPath();

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose JavaRecord output file");
        chooser.setInitialFileName(suggested.getFileName().toString());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("MP4 video", "*.mp4"));

        try {
            Files.createDirectories(suggested.getParent());
            chooser.setInitialDirectory(suggested.getParent().toFile());
        } catch (Exception exception) {
            log("Could not create suggested output folder: " + exception.getMessage());
        }

        File selected = chooser.showSaveDialog(stage);

        if (selected != null) {
            outputFileField.setText(selected.toPath().toAbsolutePath().toString());
        }
    }

    private void selectExistingVideo(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select existing video to upload");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Video files", "*.mp4", "*.mov", "*.mkv", "*.webm", "*.avi"),
                new FileChooser.ExtensionFilter("All files", "*.*")
        );

        File selected = chooser.showOpenDialog(stage);

        if (selected != null) {
            lastRecording = selected.toPath().toAbsolutePath();
            log("Selected existing video: " + lastRecording);
        }
    }

    private Path resolveOutputPath() {
        String text = outputFileField.getText() == null ? "" : outputFileField.getText().trim();

        if (text.isBlank()) {
            return suggestedOutputPath();
        }

        Path path = Paths.get(text).toAbsolutePath();

        if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp4")) {
            throw new IllegalArgumentException("Output file must end with .mp4");
        }

        return path;
    }

    private Path resolveUploadFile() {
        Path candidate = lastRecording;

        if (candidate == null) {
            String outputText = outputFileField.getText() == null ? "" : outputFileField.getText().trim();

            if (!outputText.isBlank()) {
                candidate = Paths.get(outputText).toAbsolutePath();
            }
        }

        if (candidate == null) {
            showError("No video selected", new IllegalStateException("Record a video or select an existing video first."));
            return null;
        }

        if (!Files.exists(candidate)) {
            showError("Video file does not exist", new IllegalStateException(candidate.toString()));
            return null;
        }

        return candidate.toAbsolutePath();
    }

    private Path suggestedOutputPath() {
        String fileName = "JavaRecord-" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) +
                ".mp4";

        return Paths.get(System.getProperty("user.home"), "Videos", "JavaRecord", fileName)
                .toAbsolutePath();
    }

    private void openUrl(String url) {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                log("Open this URL manually: " + url);
                return;
            }

            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception exception) {
            log("Could not open browser. URL: " + url);
            log("Browser error: " + exception.getMessage());
        }
    }

    private void showError(String header, Throwable throwable) {
        String message = throwable == null ? "Unknown error" : throwable.getMessage();

        log("ERROR: " + header + ". " + message);

        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("JavaRecord Error");
        alert.setHeaderText(header);
        alert.setContentText(message == null ? String.valueOf(throwable) : message);
        alert.showAndWait();
    }

    private void log(String message) {
        String line = "[" + LocalTime.now().format(LOG_TIME_FORMAT) + "] " + message + System.lineSeparator();

        Runnable append = () -> {
            if (logArea != null) {
                logArea.appendText(line);
                logArea.setScrollTop(Double.MAX_VALUE);
            }
        };

        if (Platform.isFxApplicationThread()) {
            append.run();
        } else {
            Platform.runLater(append);
        }
    }

    private static final class RecorderService {

        private Process process;
        private Path outputFile;

        public synchronized void start(
                String ffmpegPath,
                List<String> inputArgs,
                Path outputFile,
                Consumer<String> log
        ) throws IOException {
            if (isRecording()) {
                throw new IllegalStateException("A recording is already running.");
            }

            if (ffmpegPath == null || ffmpegPath.isBlank()) {
                throw new IllegalArgumentException("FFmpeg path cannot be empty.");
            }

            if (inputArgs == null || inputArgs.isEmpty()) {
                throw new IllegalArgumentException("FFmpeg input args cannot be empty.");
            }

            if (outputFile == null) {
                throw new IllegalArgumentException("Output file cannot be null.");
            }

            Path absoluteOutput = outputFile.toAbsolutePath();
            Path parent = absoluteOutput.getParent();

            if (parent != null) {
                Files.createDirectories(parent);
            }

            List<String> command = new ArrayList<>();
            command.add(ffmpegPath);
            command.add("-y");
            command.addAll(inputArgs);
            command.add("-c:v");
            command.add("libx264");
            command.add("-preset");
            command.add("veryfast");
            command.add("-pix_fmt");
            command.add("yuv420p");
            command.add("-movflags");
            command.add("+faststart");
            command.add(absoluteOutput.toString());

            log.accept("Starting FFmpeg:");
            log.accept(command.stream().map(RecorderService::quoteIfNeeded).collect(Collectors.joining(" ")));

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(false);

            this.process = builder.start();
            this.outputFile = absoluteOutput;

            pumpStream(this.process.getInputStream(), log, "ffmpeg");
            pumpStream(this.process.getErrorStream(), log, "ffmpeg");
        }

        public synchronized void stop(Consumer<String> log) throws IOException, InterruptedException {
            if (process == null || !process.isAlive()) {
                process = null;
                return;
            }

            Process runningProcess = process;

            log.accept("Stopping FFmpeg gracefully...");

            try {
                OutputStream stdin = runningProcess.getOutputStream();
                stdin.write('q');
                stdin.write(System.lineSeparator().getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            } catch (IOException exception) {
                log.accept("Could not send graceful quit to FFmpeg: " + exception.getMessage());
            }

            boolean exited = runningProcess.waitFor(12, TimeUnit.SECONDS);

            if (!exited) {
                log.accept("FFmpeg did not exit in time. Destroying process...");
                runningProcess.destroy();

                exited = runningProcess.waitFor(4, TimeUnit.SECONDS);

                if (!exited) {
                    log.accept("FFmpeg still running. Forcibly destroying process...");
                    runningProcess.destroyForcibly();
                    runningProcess.waitFor(4, TimeUnit.SECONDS);
                }
            }

            int exitCode = runningProcess.exitValue();

            if (exitCode == 0) {
                log.accept("FFmpeg exited successfully.");
            } else {
                log.accept("FFmpeg exited with code " + exitCode + ".");
            }

            log.accept("Output file: " + outputFile);
            process = null;
        }

        public synchronized boolean isRecording() {
            return process != null && process.isAlive();
        }

        private static void pumpStream(InputStream inputStream, Consumer<String> log, String prefix) {
            Thread thread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(inputStream, StandardCharsets.UTF_8)
                )) {
                    String line;

                    while ((line = reader.readLine()) != null) {
                        if (!line.isBlank()) {
                            log.accept(prefix + ": " + line);
                        }
                    }
                } catch (IOException ignored) {
                    // FFmpeg process termination commonly closes streams.
                }
            }, "javarecord-" + prefix + "-pump");

            thread.setDaemon(true);
            thread.start();
        }

        private static String quoteIfNeeded(String value) {
            if (value == null) {
                return "";
            }

            boolean needsQuotes = value.chars().anyMatch(Character::isWhitespace);

            if (!needsQuotes) {
                return value;
            }

            return "\"" + value.replace("\"", "\\\"") + "\"";
        }
    }

    private static final class YouTubeUploader {

        private static final String APPLICATION_NAME = "JavaRecord";
        private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
        private static final List<String> SCOPES = List.of(YouTubeScopes.YOUTUBE_UPLOAD);

        public String upload(Path videoPath, UploadMetadata metadata, Consumer<String> log) throws Exception {
            if (videoPath == null || !Files.exists(videoPath)) {
                throw new FileNotFoundException("Video file not found: " + videoPath);
            }

            if (metadata == null) {
                throw new IllegalArgumentException("Upload metadata cannot be null.");
            }

            if (metadata.commentSetting() != CommentSetting.ALLOW_ALL) {
                log.accept("Comment preference selected: " + metadata.commentSetting());
                log.accept("YouTube may require final comment changes in YouTube Studio.");
            }

            NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            Credential credential = authorize(httpTransport, log);

            YouTube youtube = new YouTube.Builder(httpTransport, JSON_FACTORY, credential)
                    .setApplicationName(APPLICATION_NAME)
                    .build();

            Video video = new Video();

            VideoSnippet snippet = new VideoSnippet();
            snippet.setTitle(metadata.title());
            snippet.setDescription(metadata.description());
            snippet.setCategoryId("22");

            VideoStatus status = new VideoStatus();
            status.setPrivacyStatus(metadata.visibility().apiValue());
            status.setSelfDeclaredMadeForKids(metadata.madeForKids());

            video.setSnippet(snippet);
            video.setStatus(status);

            String contentType = Files.probeContentType(videoPath);

            if (contentType == null || contentType.isBlank()) {
                contentType = "video/mp4";
            }

            long fileSize = Files.size(videoPath);

            log.accept("Uploading " + videoPath.getFileName() + " (" + fileSize + " bytes)");
            log.accept("Title: " + metadata.title());

            try (InputStream rawInputStream = Files.newInputStream(videoPath);
                 BufferedInputStream bufferedInputStream = new BufferedInputStream(rawInputStream)) {

                InputStreamContent mediaContent = new InputStreamContent(contentType, bufferedInputStream);
                mediaContent.setLength(fileSize);

                YouTube.Videos.Insert insertRequest =
                        youtube.videos().insert(List.of("snippet", "status"), video, mediaContent);

                MediaHttpUploader uploader = insertRequest.getMediaHttpUploader();
                uploader.setDirectUploadEnabled(false);
                uploader.setProgressListener(progressListener(log));

                Video response = insertRequest.execute();

                if (response == null || response.getId() == null || response.getId().isBlank()) {
                    throw new IllegalStateException("YouTube upload finished but no video ID was returned.");
                }

                return response.getId();
            }
        }

        private Credential authorize(NetHttpTransport httpTransport, Consumer<String> log) throws Exception {
            Path clientSecretPath = resolveClientSecretFile();
            Path appHome = appHome();
            Path tokenDirectory = appHome.resolve("tokens");

            Files.createDirectories(tokenDirectory);

            log.accept("Using OAuth client file: " + clientSecretPath);
            log.accept("OAuth tokens directory: " + tokenDirectory);

            try (InputStream inputStream = Files.newInputStream(clientSecretPath);
                 InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {

                GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, reader);

                GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                        httpTransport,
                        JSON_FACTORY,
                        clientSecrets,
                        SCOPES
                )
                        .setDataStoreFactory(new FileDataStoreFactory(tokenDirectory.toFile()))
                        .setAccessType("offline")
                        .build();

                LocalServerReceiver receiver = new LocalServerReceiver.Builder()
                        .setPort(8888)
                        .build();

                log.accept("Opening browser for YouTube authorization if needed...");

                return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
            }
        }

        private static MediaHttpUploaderProgressListener progressListener(Consumer<String> log) {
            return uploader -> {
                switch (uploader.getUploadState()) {
                    case INITIATION_STARTED ->
                            log.accept("YouTube upload initiation started.");

                    case INITIATION_COMPLETE ->
                            log.accept("YouTube upload initiation complete.");

                    case MEDIA_IN_PROGRESS -> {
                        double percentage = uploader.getProgress() * 100.0;
                        log.accept(String.format("Upload progress: %.2f%%", percentage));
                    }

                    case MEDIA_COMPLETE ->
                            log.accept("Media upload complete.");

                    case NOT_STARTED ->
                            log.accept("Upload not started yet.");
                }
            };
        }

        private static Path resolveClientSecretFile() throws Exception {
            Path local = Path.of("client_secret.json").toAbsolutePath();
            Path appHome = appHome().resolve("client_secret.json").toAbsolutePath();

            if (Files.exists(local)) {
                return local;
            }

            if (Files.exists(appHome)) {
                return appHome;
            }

            throw new FileNotFoundException(
                    "Missing client_secret.json. Put your Google OAuth Desktop client JSON at either:\n" +
                            local + "\n" +
                            "or\n" +
                            appHome
            );
        }

        private static Path appHome() {
            return Path.of(System.getProperty("user.home"), ".javarecord").toAbsolutePath();
        }
    }

    private record UploadMetadata(
            String title,
            String description,
            VideoVisibility visibility,
            boolean madeForKids,
            CommentSetting commentSetting
    ) {
        private UploadMetadata {
            if (title == null || title.isBlank()) {
                title = "JavaRecord Upload " +
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } else {
                title = title.trim();
            }

            if (description == null) {
                description = "";
            }

            visibility = Objects.requireNonNullElse(visibility, VideoVisibility.UNLISTED);
            commentSetting = Objects.requireNonNullElse(commentSetting, CommentSetting.ALLOW_ALL);
        }
    }

    private enum VideoVisibility {

        UNLISTED("Unlisted", "unlisted"),
        PRIVATE("Private", "private"),
        PUBLIC("Public", "public");

        private final String label;
        private final String apiValue;

        VideoVisibility(String label, String apiValue) {
            this.label = label;
            this.apiValue = apiValue;
        }

        public String apiValue() {
            return apiValue;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private enum CommentSetting {

        ALLOW_ALL("Allow all comments"),
        HOLD_POTENTIALLY_INAPPROPRIATE("Hold potentially inappropriate comments"),
        HOLD_ALL("Hold all comments for review"),
        DISABLE_COMMENTS("Disable comments");

        private final String label;

        CommentSetting(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class CommandLineParser {

        private CommandLineParser() {
        }

        public static List<String> split(String commandLine) {
            if (commandLine == null || commandLine.isBlank()) {
                return List.of();
            }

            List<String> result = new ArrayList<>();
            StringBuilder current = new StringBuilder();

            boolean insideQuote = false;
            char quoteCharacter = '\0';

            for (int index = 0; index < commandLine.length(); index++) {
                char character = commandLine.charAt(index);

                if (character == '\\' && index + 1 < commandLine.length()) {
                    char next = commandLine.charAt(index + 1);

                    if (next == '"' || next == '\'' || next == '\\') {
                        current.append(next);
                        index++;
                        continue;
                    }
                }

                if (insideQuote) {
                    if (character == quoteCharacter) {
                        insideQuote = false;
                    } else {
                        current.append(character);
                    }

                    continue;
                }

                if (character == '"' || character == '\'') {
                    insideQuote = true;
                    quoteCharacter = character;
                    continue;
                }

                if (Character.isWhitespace(character)) {
                    if (current.length() > 0) {
                        result.add(current.toString());
                        current.setLength(0);
                    }

                    continue;
                }

                current.append(character);
            }

            if (insideQuote) {
                throw new IllegalArgumentException("Unclosed quote in FFmpeg input args.");
            }

            if (current.length() > 0) {
                result.add(current.toString());
            }

            return List.copyOf(result);
        }
    }

    private static final class PlatformDefaults {

        private PlatformDefaults() {
        }

        public static String defaultFfmpegInputArgs() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

            if (os.contains("win")) {
                return "-f gdigrab -framerate 30 -i desktop";
            }

            if (os.contains("mac")) {
                return "-f avfoundation -framerate 30 -i \"1:none\"";
            }

            String display = System.getenv("DISPLAY");

            if (display == null || display.isBlank()) {
                display = ":0.0";
            }

            return "-f x11grab -video_size 1920x1080 -framerate 30 -i " + display;
        }
    }
    public static final class Launcher {
        public static void main(String[] args) {
            Main.main(args);
        }
    }
}