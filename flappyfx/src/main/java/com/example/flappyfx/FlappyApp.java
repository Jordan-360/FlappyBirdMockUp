package com.example.flappyfx;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.scene.text.Text;

import javax.sound.sampled.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drop-in "final" version that keeps your existing file/class/package:
 * - DO NOT rename anything
 * - Replace your current FlappyApp.java with this
 *
 * Includes:
 * ✅ Start screen + Press SPACE to start
 * ✅ Pause (P) + Restart (R)
 * ✅ Difficulty scaling (pipe speed/gap/spawn rate)
 * ✅ High score saving (local file)
 * ✅ Better collision (circle vs rect)
 * ✅ Particles (flap/score/crash)
 * ✅ Procedural bird frames (no downloads) — transparent snapshot (no white box)
 * ✅ Procedural pipes + scrolling ground (no downloads)
 * ✅ Sound effects (generated tones; no audio files)
 * ✅ Mario-like background music (generated chiptune)
 * ✅ Volume slider (controls BGM + SFX)
 * ✅ Hitbox toggle (H), Mute toggle (M)
 */
public class FlappyApp extends Application {

    // Window
    private static final int W = 800;
    private static final int H = 500;

    // Physics
    private static final double GRAVITY = 1400;
    private static final double FLAP_VELOCITY = -420;
    private static final double MAX_FALL_SPEED = 1100;

    // Pipes / difficulty
    private static final double PIPE_BASE_SPEED = 260;
    private static final double PIPE_BASE_GAP = 175;
    private static final double PIPE_MIN_GAP = 135;
    private static final double PIPE_WIDTH = 80;

    private static final double PIPE_SPAWN_BASE = 1.35;
    private static final double PIPE_SPAWN_MIN  = 1.05;  

    // Ground
    private final double groundH = 60;
    private final double groundY = H - groundH;

    // Bird
    private double bW = 42, bH = 30;
    private double bx = 200, by = 250, bVy = 0;

    // Game state
    private enum Mode { START, PLAYING, PAUSED, GAME_OVER }
    private Mode mode = Mode.START;

    private boolean flapRequested = false;
    private boolean showHitboxes = false;
    private boolean muted = false;

    // Master volume (0..1) controlled by slider
    private volatile double masterVolume = 0.65;

    // Score
    private int score = 0;
    private int highScore = 0;

    // Time
    private double t = 0;        // total time
    private double runTime = 0;  // time spent PLAYING
    private double spawnTimer = 0;

    // Death animation
    private double deathAngleDeg = 0;
    private double deathSpinDegPerSec = 420;

    // Procedural “assets” (no downloads)
    private Image[] birdFrames;    // 3-frame flap
    private Image pipeBodyTex;     // tiled
    private Image pipeRimTex;      // rim strip
    private Image groundTex;       // tiled

    // Ground scroll
    private double groundScroll = 0;

    // Random
    private final Random rng = new Random();

    // Pipes
    private static class PipePair {
        double x;
        double gapY;
        boolean scored;
        PipePair(double x, double gapY) { this.x = x; this.gapY = gapY; }
    }
    private final List<PipePair> pipes = new ArrayList<>();

    // Particles
    private static class Particle {
        double x, y, vx, vy, life, maxLife, size;
        Particle(double x, double y, double vx, double vy, double life, double size) {
            this.x = x; this.y = y; this.vx = vx; this.vy = vy;
            this.life = life; this.maxLife = life; this.size = size;
        }
        double alpha() { return Math.max(0, Math.min(1, life / Math.max(1e-6, maxLife))); }
    }
    private final List<Particle> particles = new ArrayList<>();

    // High score persistence
    private final Path highScorePath = Paths.get(System.getProperty("user.home"), ".flappyfx", "highscore.txt");

    // Audio
    private final Sfx sfx = new Sfx(() -> muted, () -> masterVolume);
    private final Bgm bgm = new Bgm(() -> muted, () -> masterVolume);
    private Mode lastMode = null;

    @Override
    public void start(Stage stage) {
        Canvas canvas = new Canvas(W, H);
        GraphicsContext g = canvas.getGraphicsContext2D();

        // --- UI overlay (volume slider) ---

        Label volLabel = new Label("Volume");
        Slider volSlider = new Slider(0, 1, masterVolume);

        volSlider.setPrefWidth(120);
        volSlider.setMaxWidth(120);

        volSlider.valueProperty().addListener((obs, oldV, newV) -> masterVolume = newV.doubleValue());

        VBox hud = new VBox(4, volLabel, volSlider);
        hud.setPadding(new Insets(10));
        hud.setAlignment(Pos.TOP_LEFT);

        hud.setTranslateY(26);

        hud.setMouseTransparent(false);

        StackPane root = new StackPane(canvas, hud);
        StackPane.setAlignment(hud, Pos.TOP_LEFT);

        Scene scene = new Scene(root);
        scene.setOnKeyPressed(e -> {
            KeyCode code = e.getCode();

            if (code == KeyCode.H) showHitboxes = !showHitboxes;
            if (code == KeyCode.M) muted = !muted;

            if (code == KeyCode.R) resetToStart();

            if (code == KeyCode.P) {
                if (mode == Mode.PLAYING) mode = Mode.PAUSED;
                else if (mode == Mode.PAUSED) mode = Mode.PLAYING;
            }

            if (code == KeyCode.SPACE || code == KeyCode.UP) {
                flapRequested = true;
            }
        });

        stage.setTitle("Flappy Bird");
        stage.setScene(scene);
        stage.show();

        stage.setOnCloseRequest(e -> bgm.stop());

        // Load high score
        highScore = loadHighScore();

        // Build procedural visuals (use OFFSCREEN canvas so we don't resize the game canvas)
        buildProceduralAssets();

        // Start screen
        resetToStart();

        AnimationTimer loop = new AnimationTimer() {
            long last = 0;
            @Override public void handle(long now) {
                if (last == 0) { last = now; return; }
                double dt = (now - last) / 1_000_000_000.0;
                last = now;

                dt = Math.min(dt, 0.033); // clamp to avoid huge jumps
                update(dt);
                render(g);
            }
        };
        loop.start();
    }

    // --------------------
    // Difficulty curve
    // --------------------
    private double difficulty01() {
        double x = runTime / 70.0; // ~1 around 70s
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x); // smoothstep
    }
    private double pipeSpeed() {
        return PIPE_BASE_SPEED + 100 * difficulty01(); // 260 -> 360
    }
    private double pipeGap() {
        return Math.max(PIPE_MIN_GAP, PIPE_BASE_GAP - 40 * difficulty01()); // 175 -> 135
    }
    private double spawnEvery() {
        return Math.max(PIPE_SPAWN_MIN, PIPE_SPAWN_BASE - 0.30 * difficulty01());
    }

    // --------------------
    // Reset
    // --------------------
    private void resetToStart() {
        bx = 200;
        by = H / 2.0;
        bVy = 0;

        pipes.clear();
        particles.clear();

        score = 0;
        spawnTimer = 0;

        mode = Mode.START;
        deathAngleDeg = 0;
        deathSpinDegPerSec = 420;

        t = 0;
        runTime = 0;

        groundScroll = 0;
        flapRequested = false;

        // seed a pipe
        pipes.add(new PipePair(W + 240, randomGapY()));
    }

    private double randomGapY() {
        double margin = 95;
        double topLimit = margin;
        double bottomLimit = groundY - margin;
        return topLimit + rng.nextDouble() * Math.max(1, (bottomLimit - topLimit));
    }

    private Rectangle2D topRect(PipePair p) {
        double gap = pipeGap();
        double topH = p.gapY - gap / 2.0;
        return new Rectangle2D(p.x, 0, PIPE_WIDTH, topH);
    }

    private Rectangle2D bottomRect(PipePair p) {
        double gap = pipeGap();
        double topH = p.gapY - gap / 2.0;
        double bottomY = topH + gap;
        return new Rectangle2D(p.x, bottomY, PIPE_WIDTH, groundY - bottomY);
    }

    // --------------------
    // Better collision: circle vs rectangle
    // --------------------
    private double birdCx() { return bx + bW * 0.52; }
    private double birdCy() { return by + bH * 0.52; }
    private double birdRadius() { return Math.min(bW, bH) * 0.36; } // forgiving

    private boolean circleIntersectsRect(double cx, double cy, double r, Rectangle2D rect) {
        double closestX = clamp(cx, rect.getMinX(), rect.getMaxX());
        double closestY = clamp(cy, rect.getMinY(), rect.getMaxY());
        double dx = cx - closestX;
        double dy = cy - closestY;
        return (dx * dx + dy * dy) <= r * r;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // --------------------
    // Update
    // --------------------
    private void update(double dt) {
        t += dt;

        // Start/stop background music based on mode
        if (lastMode != mode) {
            if (mode == Mode.PLAYING) bgm.start();
            else bgm.stop();
            lastMode = mode;
        }

        // Start screen: SPACE starts and flaps
        if (mode == Mode.START) {
            if (flapRequested) {
                flapRequested = false;
                mode = Mode.PLAYING;
                doFlap();
            } else {
                flapRequested = false;
            }
        } else if (mode == Mode.PAUSED) {
            flapRequested = false;
            return; // freeze game
        } else if (mode == Mode.PLAYING) {
            if (flapRequested) {
                flapRequested = false;
                doFlap();
            } else {
                flapRequested = false;
            }
        } else { // GAME_OVER
            flapRequested = false;
        }

        // Scroll ground always (except paused early return)
        groundScroll = (groundScroll + pipeSpeed() * dt) % groundTex.getWidth();

        // Particles always (except paused)
        updateParticles(dt);

        // Bird physics
        if (mode == Mode.START) {
            double bob = Math.sin(t * 2.2) * 6.0;
            by = (H / 2.0) + bob;
            bVy = 0;
        } else {
            bVy += GRAVITY * dt;
            bVy = Math.min(MAX_FALL_SPEED, bVy);
            by += bVy * dt;

            // ceiling clamp
            if (by < 0) { by = 0; bVy = 0; }

            // ground collision
            if (by + bH > groundY) {
                by = groundY - bH;
                if (mode == Mode.PLAYING) triggerGameOver(true);
                else if (mode == Mode.GAME_OVER) bVy = 0;
            }
        }

        // Pipes
        if (mode == Mode.PLAYING) {
            runTime += dt;

            // spawn
            spawnTimer += dt;
            if (spawnTimer >= spawnEvery()) {
                spawnTimer = 0;
                pipes.add(new PipePair(W + 60, randomGapY()));
            }

            // move + score + collision + cleanup
            double speed = pipeSpeed();

            Iterator<PipePair> it = pipes.iterator();
            while (it.hasNext()) {
                PipePair p = it.next();
                p.x -= speed * dt;

                if (!p.scored && p.x + PIPE_WIDTH < bx) {
                    p.scored = true;
                    score++;
                    highScore = Math.max(highScore, score);
                    saveHighScore(highScore);
                    spawnScoreParticles();
                    sfx.score();
                }

                Rectangle2D top = topRect(p);
                Rectangle2D bot = bottomRect(p);

                if (circleIntersectsRect(birdCx(), birdCy(), birdRadius(), top) ||
                        circleIntersectsRect(birdCx(), birdCy(), birdRadius(), bot)) {
                    triggerGameOver(false);
                }

                if (p.x + PIPE_WIDTH < -120) it.remove();
            }
        }

        // Death spin
        if (mode == Mode.GAME_OVER) {
            deathAngleDeg += deathSpinDegPerSec * dt;
            deathSpinDegPerSec = Math.min(900, 420 + Math.max(0, bVy) * 0.35);
        }
    }

    private void doFlap() {
        bVy = FLAP_VELOCITY;
        spawnFlapParticles();
        sfx.flap();
    }

    private void triggerGameOver(boolean hitGround) {
        if (mode == Mode.GAME_OVER) return;
        mode = Mode.GAME_OVER;

        deathAngleDeg = clampDeg(vyToAngleDeg(bVy));
        deathSpinDegPerSec = hitGround ? 600 : 420;

        spawnCrashParticles();
        sfx.hit();
    }

    private static double clampDeg(double a) {
        while (a > 180) a -= 360;
        while (a < -180) a += 360;
        return a;
    }

    private double vyToAngleDeg(double vy) {
        double angle = vy * 0.08;
        return Math.max(-25, Math.min(65, angle));
    }

    // --------------------
    // Render
    // --------------------
    private void render(GraphicsContext g) {
        // Sky
        g.setFill(Color.rgb(120, 200, 255));
        g.fillRect(0, 0, W, H);

        // Haze
        g.setFill(Color.rgb(105, 190, 245, 0.55));
        g.fillRect(0, H * 0.70, W, H * 0.30);

        // Pipes
        for (PipePair p : pipes) {
            Rectangle2D top = topRect(p);
            Rectangle2D bot = bottomRect(p);

            drawTexturedPipe(g, top.getMinX(), top.getMinY(), top.getWidth(), top.getHeight(), true);
            drawTexturedPipe(g, bot.getMinX(), bot.getMinY(), bot.getWidth(), bot.getHeight(), false);

            if (showHitboxes) {
                g.setStroke(Color.RED);
                g.setLineWidth(2);
                g.strokeRect(top.getMinX(), top.getMinY(), top.getWidth(), top.getHeight());
                g.strokeRect(bot.getMinX(), bot.getMinY(), bot.getWidth(), bot.getHeight());
            }
        }

        // Ground
        drawScrollingGround(g);

        // Particles
        drawParticles(g);

        // Bird
        double angle = (mode == Mode.GAME_OVER) ? deathAngleDeg : vyToAngleDeg(bVy);
        drawBirdSprite(g, bx, by, bW, bH, angle);

        // Hitbox
        if (showHitboxes) {
            g.setStroke(Color.MAGENTA);
            g.setLineWidth(2);
            double r = birdRadius();
            g.strokeOval(birdCx() - r, birdCy() - r, r * 2, r * 2);
        }

        // UI
        g.setFill(Color.BLACK);
        g.fillText(
                "Score: " + score +
                        "   High: " + highScore +
                        "   (Space/Up=flap/start, P=pause, R=restart, H=hitboxes, M=mute)",
                16, 24
        );

        // Overlays
        if (mode == Mode.START) overlay(g, "FLAPPY FX", "Press SPACE to start");
        else if (mode == Mode.PAUSED) overlay(g, "PAUSED", "Press P to resume");
        else if (mode == Mode.GAME_OVER) overlay(g, "GAME OVER", "Press R to restart");
    }

    private void overlay(GraphicsContext g, String title, String subtitle) {
        // Darken background
        g.setFill(Color.color(0, 0, 0, 0.35));
        g.fillRect(0, 0, W, H);

        g.setFill(Color.WHITE);

        // ---- Title ----
        Text titleText = new Text(title);
        titleText.setFont(g.getFont());
        double titleWidth = titleText.getLayoutBounds().getWidth();

        double titleX = (W - titleWidth) / 2.0;
        double titleY = H / 2.0 - 12;
        g.fillText(title, titleX, titleY);

        // ---- Subtitle ----
        Text subText = new Text(subtitle);
        subText.setFont(g.getFont());
        double subWidth = subText.getLayoutBounds().getWidth();

        double subX = (W - subWidth) / 2.0;
        double subY = H / 2.0 + 14;
        g.fillText(subtitle, subX, subY);
    }

    // --------------------
    // Bird sprite (procedural frames)
    // --------------------
    private void drawBirdSprite(GraphicsContext g, double x, double y, double w, double h, double angleDeg) {
        int frameCount = birdFrames.length;

        // animate in START and PLAYING; freeze in GAME_OVER
        double animT = (mode == Mode.GAME_OVER) ? 0 : t;
        int frame = (int) (animT * 12) % frameCount;

        g.save();
        g.translate(x + w / 2, y + h / 2);
        g.rotate(angleDeg);
        g.translate(-w / 2, -h / 2);
        g.drawImage(birdFrames[frame], 0, 0, w, h);
        g.restore();
    }

    // --------------------
    // Textured pipes + scrolling ground
    // --------------------
    private void drawTexturedPipe(GraphicsContext g, double x, double y, double w, double h, boolean lipAtBottom) {
        if (h <= 0) return;

        double tw = pipeBodyTex.getWidth();
        double th = pipeBodyTex.getHeight();

        // tile vertically
        for (double yy = y; yy < y + h; yy += th) {
            double drawH = Math.min(th, (y + h) - yy);
            g.drawImage(pipeBodyTex, 0, 0, tw, drawH, x, yy, w, drawH);
        }

        // rim
        double rimH = 22;
        double rimY = lipAtBottom ? (y + h - rimH) : y;
        g.drawImage(pipeRimTex, x - 9, rimY, w + 18, rimH);

        // outline
        g.setStroke(Color.rgb(20, 70, 20));
        g.setLineWidth(3);
        g.strokeRoundRect(x, y, w, h, 12, 12);
        g.strokeRoundRect(x - 9, rimY, w + 18, rimH, 14, 14);
    }

    private void drawScrollingGround(GraphicsContext g) {
        // dirt
        g.setFill(Color.rgb(140, 105, 60));
        g.fillRect(0, groundY, W, groundH);

        // grass band
        g.setFill(Color.rgb(60, 170, 70));
        g.fillRect(0, groundY, W, 14);

        // tile texture
        double texW = groundTex.getWidth();
        double x0 = -groundScroll;
        for (double x = x0; x < W; x += texW) {
            g.drawImage(groundTex, x, groundY, texW, groundH);
        }

        // edge line
        g.setStroke(Color.rgb(80, 60, 35));
        g.setLineWidth(2);
        g.strokeLine(0, groundY, W, groundY);
    }

    // --------------------
    // Particles
    // --------------------
    private void updateParticles(double dt) {
        Iterator<Particle> it = particles.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            p.life -= dt;
            if (p.life <= 0) { it.remove(); continue; }
            p.vy += 900 * dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
        }
        if (particles.size() > 450) {
            particles.subList(0, particles.size() - 450).clear();
        }
    }

    private void drawParticles(GraphicsContext g) {
        for (Particle p : particles) {
            double a = p.alpha();
            g.setFill(Color.rgb(255, 230, 140, a));
            g.fillOval(p.x, p.y, p.size, p.size);
        }
    }

    private void spawnFlapParticles() {
        double x = bx + bW * 0.15;
        double y = by + bH * 0.65;
        for (int i = 0; i < 14; i++) {
            double ang = Math.PI + (rng.nextDouble() * 0.9 - 0.45);
            double sp = 90 + rng.nextDouble() * 120;
            double vx = Math.cos(ang) * sp;
            double vy = Math.sin(ang) * sp - 60;
            double life = 0.35 + rng.nextDouble() * 0.25;
            double size = 2 + rng.nextDouble() * 3;
            particles.add(new Particle(x, y, vx, vy, life, size));
        }
    }

    private void spawnScoreParticles() {
        double x = bx + bW * 0.5;
        double y = by - 10;
        for (int i = 0; i < 18; i++) {
            double ang = -Math.PI / 2 + (rng.nextDouble() * 1.2 - 0.6);
            double sp = 120 + rng.nextDouble() * 180;
            double vx = Math.cos(ang) * sp;
            double vy = Math.sin(ang) * sp;
            double life = 0.45 + rng.nextDouble() * 0.25;
            double size = 2 + rng.nextDouble() * 3.5;
            particles.add(new Particle(x, y, vx, vy, life, size));
        }
    }

    private void spawnCrashParticles() {
        double x = birdCx();
        double y = birdCy();
        for (int i = 0; i < 38; i++) {
            double ang = rng.nextDouble() * Math.PI * 2;
            double sp = 120 + rng.nextDouble() * 320;
            double vx = Math.cos(ang) * sp;
            double vy = Math.sin(ang) * sp - 80;
            double life = 0.55 + rng.nextDouble() * 0.35;
            double size = 2 + rng.nextDouble() * 4;
            particles.add(new Particle(x, y, vx, vy, life, size));
        }
    }

    // --------------------
    // Procedural asset generation (OFFSCREEN)
    // --------------------
    private void buildProceduralAssets() {
        Canvas scratch = new Canvas(1, 1);

        birdFrames = new Image[3];
        for (int i = 0; i < birdFrames.length; i++) birdFrames[i] = makeBirdFrame(scratch, i);

        pipeBodyTex = makePipeBodyTexture(scratch);
        pipeRimTex = makePipeRimTexture(scratch);
        groundTex = makeGroundTexture(scratch);
    }

    // Transparent snapshot so there's no white box behind the bird.
    private Image makeBirdFrame(Canvas c, int frameIndex) {
        int iw = 64, ih = 48;
        c.setWidth(iw);
        c.setHeight(ih);
        GraphicsContext g = c.getGraphicsContext2D();

        g.clearRect(0, 0, iw, ih);

        double lift = (frameIndex == 0) ? 1.0 : (frameIndex == 1 ? 0.5 : 0.0);

        // body
        g.setFill(Color.GOLD);
        g.fillOval(10, 10, 40, 28);

        // belly
        g.setFill(Color.rgb(255, 240, 170, 0.95));
        g.fillOval(18, 18, 30, 18);

        // tail
        g.setFill(Color.rgb(230, 170, 30));
        g.fillPolygon(new double[]{12, 2, 12}, new double[]{26, 24, 18}, 3);

        // wing
        double wx = 18;
        double wy = 24 - (6 * lift);
        double ww = 22;
        double wh = 16 + (4 * lift);

        g.setFill(Color.rgb(220, 140, 20, 0.55));
        g.fillOval(wx + 3, wy + 3, ww, wh);

        g.setFill(Color.ORANGE);
        g.fillOval(wx, wy, ww, wh);

        // eye
        g.setFill(Color.WHITE);
        g.fillOval(38, 16, 10, 12);
        g.setFill(Color.BLACK);
        g.fillOval(42, 20, 4, 5);

        // beak
        g.setFill(Color.rgb(255, 120, 40));
        g.fillPolygon(new double[]{50, 62, 50}, new double[]{22, 24, 28}, 3);
        g.setFill(Color.rgb(250, 180, 70, 0.9));
        g.fillPolygon(new double[]{51, 60, 51}, new double[]{22.5, 24, 27.5}, 3);

        // outline
        g.setStroke(Color.rgb(120, 80, 10));
        g.setLineWidth(2);
        g.strokeOval(10, 10, 40, 28);

        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);

        WritableImage out = new WritableImage(iw, ih);
        c.snapshot(params, out);
        return out;
    }

    private Image makePipeBodyTexture(Canvas c) {
        int iw = 64, ih = 64;
        c.setWidth(iw);
        c.setHeight(ih);
        GraphicsContext g = c.getGraphicsContext2D();
        g.clearRect(0, 0, iw, ih);

        Color bodyDark = Color.rgb(25, 120, 35);
        Color bodyLight = Color.rgb(60, 190, 70);
        Color highlight = Color.rgb(140, 255, 150);

        g.setFill(bodyDark);
        g.fillRect(0, 0, iw, ih);

        int bands = 12;
        for (int i = 0; i < bands; i++) {
            double tt = i / (double) (bands - 1);
            double x = tt * iw;
            double w = iw / (double) bands + 1;
            double center = 1.0 - Math.abs(tt - 0.5) * 2.0;
            Color band = bodyDark.interpolate(bodyLight, 0.20 + 0.55 * center);
            g.setFill(band);
            g.fillRect(x, 0, w, ih);
        }

        g.setFill(highlight.deriveColor(0, 1, 1, 0.40));
        g.fillRoundRect(iw * 0.12, 6, iw * 0.16, ih - 12, 10, 10);

        g.setFill(Color.rgb(10, 70, 20, 0.20));
        for (int i = 0; i < 14; i++) {
            double sx = 6 + rng.nextDouble() * (iw - 12);
            double sy = 6 + rng.nextDouble() * (ih - 12);
            g.fillOval(sx, sy, 2, 2);
        }

        WritableImage out = new WritableImage(iw, ih);
        c.snapshot(null, out);
        return out;
    }

    private Image makePipeRimTexture(Canvas c) {
        int iw = 96, ih = 24;
        c.setWidth(iw);
        c.setHeight(ih);
        GraphicsContext g = c.getGraphicsContext2D();
        g.clearRect(0, 0, iw, ih);

        Color outline = Color.rgb(20, 70, 20);
        Color rimLight = Color.rgb(70, 210, 85);
        Color rimDark = Color.rgb(30, 130, 40);

        g.setFill(rimLight);
        g.fillRoundRect(0, 0, iw, ih, 14, 14);

        g.setFill(rimDark.deriveColor(0, 1, 1, 0.35));
        g.fillRoundRect(0, ih * 0.45, iw, ih * 0.55, 14, 14);

        g.setStroke(outline.deriveColor(0, 1, 1, 0.9));
        g.setLineWidth(3);
        g.strokeRoundRect(3, 3, iw - 6, ih - 6, 12, 12);

        WritableImage out = new WritableImage(iw, ih);
        c.snapshot(null, out);
        return out;
    }

    private Image makeGroundTexture(Canvas c) {
        int iw = 128, ih = (int) groundH;
        c.setWidth(iw);
        c.setHeight(ih);
        GraphicsContext g = c.getGraphicsContext2D();
        g.clearRect(0, 0, iw, ih);

        g.setFill(Color.rgb(140, 105, 60));
        g.fillRect(0, 0, iw, ih);

        g.setFill(Color.rgb(60, 170, 70));
        g.fillRect(0, 0, iw, 14);

        g.setStroke(Color.rgb(40, 140, 55, 0.9));
        g.setLineWidth(2);
        for (int x = 0; x < iw; x += 10) {
            double h1 = 6 + rng.nextInt(6);
            g.strokeLine(x, 14, x + 4, 14 - h1);
        }

        g.setFill(Color.rgb(95, 70, 40, 0.35));
        for (int i = 0; i < 50; i++) {
            double x = rng.nextDouble() * iw;
            double y = 18 + rng.nextDouble() * (ih - 22);
            double r = 1 + rng.nextDouble() * 2;
            g.fillOval(x, y, r, r);
        }

        WritableImage out = new WritableImage(iw, ih);
        c.snapshot(null, out);
        return out;
    }

    // --------------------
    // High score persistence
    // --------------------
    private int loadHighScore() {
        try {
            if (!Files.exists(highScorePath)) return 0;
            String s = Files.readString(highScorePath, StandardCharsets.UTF_8).trim();
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void saveHighScore(int value) {
        try {
            Files.createDirectories(highScorePath.getParent());
            Files.writeString(
                    highScorePath, String.valueOf(value), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException ignored) {
            // ignore; game still runs
        }
    }

    // --------------------
    // SFX (volume + mute)
    // --------------------
    private static class Sfx {
        private final float sampleRate = 44100f;
        private final BoolSupplier muted;
        private final DoubleSupplier volume;

        interface BoolSupplier { boolean get(); }
        interface DoubleSupplier { double get(); }

        Sfx(BoolSupplier muted, DoubleSupplier volume) {
            this.muted = muted;
            this.volume = volume;
        }

        void flap()  { playTone(740, 60, 0.35); }
        void score() { playTone(990, 90, 0.40); }
        void hit()   { playTone(180, 160, 0.50); }

        private void playTone(double freqHz, int ms, double baseVol) {
            if (muted.get()) return;

            double master = clamp01(volume.get());
            double vol = baseVol * master;
            if (vol <= 0.0001) return;

            try {
                byte[] data = toneData(freqHz, ms, vol);
                AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false);
                Clip clip = AudioSystem.getClip();
                clip.open(fmt, data, 0, data.length);
                clip.start();
                clip.addLineListener(ev -> {
                    if (ev.getType() == LineEvent.Type.STOP) clip.close();
                });
            } catch (Exception ignored) {}
        }

        private byte[] toneData(double freqHz, int ms, double volume) {
            int samples = (int) ((ms / 1000.0) * sampleRate);
            byte[] out = new byte[samples * 2];

            double twoPiF = 2 * Math.PI * freqHz;
            int attack = (int) (samples * 0.10);
            int release = (int) (samples * 0.20);

            for (int i = 0; i < samples; i++) {
                double env = 1.0;
                if (i < attack) env = i / (double) Math.max(1, attack);
                else if (i > samples - release) env = (samples - i) / (double) Math.max(1, release);

                double s = Math.sin(twoPiF * (i / sampleRate));
                short val = (short) (s * 32767 * volume * env);

                out[i * 2] = (byte) (val & 0xff);
                out[i * 2 + 1] = (byte) ((val >> 8) & 0xff);
            }
            return out;
        }

        private static double clamp01(double v) {
            return Math.max(0.0, Math.min(1.0, v));
        }
    }

    // --------------------
    // Background music (Mario-like chiptune) — volume + mute
    // --------------------
    private static class Bgm {
        private final Sfx.BoolSupplier muted;
        private final Sfx.DoubleSupplier volume;

        private final AtomicBoolean running = new AtomicBoolean(false);
        private Thread thread;

        private final float sampleRate = 44100f;

        Bgm(Sfx.BoolSupplier muted, Sfx.DoubleSupplier volume) {
            this.muted = muted;
            this.volume = volume;
        }

        void start() {
            if (running.getAndSet(true)) return;
            thread = new Thread(this::runLoop, "BGM-Thread");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running.set(false);
        }

        private void runLoop() {
            SourceDataLine line = null;
            try {
                AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false);
                line = AudioSystem.getSourceDataLine(fmt);
                line.open(fmt, 2048 * 2);
                line.start();

                // Tempo + step timing (16th notes)
                double bpm = 150;
                double beatSec = 60.0 / bpm;
                double stepSec = beatSec / 4.0; // 16th notes
                int stepSamples = (int) (stepSec * sampleRate);
                byte[] buffer = new byte[stepSamples * 2];

                // Melody (MIDI) with REST = -1
                final int R = -1;
                int[] lead = {
                        79, 79, R, 79, 76, 79, 83, R,
                        84, R, 83, R, 79, R, 76, R,
                        79, R, 76, R, 74, 76, 79, R,
                        83, R, 79, R, 76, R, 74, R
                };

                // Bass: I V vi IV-ish
                int[] bass = {
                        48, R, 48, R, 55, R, 55, R,
                        45, R, 45, R, 53, R, 53, R,
                        48, R, 48, R, 55, R, 55, R,
                        45, R, 45, R, 53, R, 53, R
                };

                // Light harmony pulses
                int[] harm = {
                        64, R, 67, R, 64, R, 67, R,
                        60, R, 64, R, 60, R, 64, R,
                        64, R, 67, R, 64, R, 67, R,
                        60, R, 64, R, 60, R, 64, R
                };

                int step = 0;
                double leadPhase = 0, bassPhase = 0, harmPhase = 0;

                while (running.get()) {
                    double master = clamp01(volume.get());
                    if (muted.get()) master = 0.0;

                    int lm = lead[step % lead.length];
                    int bm = bass[step % bass.length];
                    int hm = harm[step % harm.length];

                    double lf = (lm == R) ? 0 : midiToHz(lm);
                    double bf = (bm == R) ? 0 : midiToHz(bm);
                    double hf = (hm == R) ? 0 : midiToHz(hm);

                    double leadVol = 0.20 * master;
                    double bassVol = 0.12 * master;
                    double harmVol = 0.06 * master;

                    for (int i = 0; i < stepSamples; i++) {
                        double env = staccatoEnv(i, stepSamples);

                        // Lead: square wave
                        double leadS = 0;
                        if (lm != R) {
                            double s = Math.sin(leadPhase);
                            leadS = (s >= 0 ? 1 : -1) * leadVol;
                            leadPhase += (2 * Math.PI * lf) / sampleRate;
                            if (leadPhase > 2 * Math.PI) leadPhase -= 2 * Math.PI;
                        }

                        // Bass: triangle-ish
                        double bassS = 0;
                        if (bm != R) {
                            bassS = triangleFromPhase(bassPhase) * bassVol;
                            bassPhase += (2 * Math.PI * bf) / sampleRate;
                            if (bassPhase > 2 * Math.PI) bassPhase -= 2 * Math.PI;
                        }

                        // Harmony: soft square
                        double harmS = 0;
                        if (hm != R) {
                            double s = Math.sin(harmPhase);
                            harmS = (s >= 0 ? 1 : -1) * harmVol;
                            harmPhase += (2 * Math.PI * hf) / sampleRate;
                            if (harmPhase > 2 * Math.PI) harmPhase -= 2 * Math.PI;
                        }

                        double mix = (leadS + bassS + harmS) * env;

                        // soft clip
                        mix = Math.max(-0.95, Math.min(0.95, mix));

                        short sample = (short) (mix * 32767);
                        buffer[i * 2] = (byte) (sample & 0xff);
                        buffer[i * 2 + 1] = (byte) ((sample >> 8) & 0xff);
                    }

                    line.write(buffer, 0, buffer.length);
                    step++;
                }

            } catch (Exception ignored) {
            } finally {
                if (line != null) {
                    try { line.stop(); } catch (Exception ignored) {}
                    try { line.close(); } catch (Exception ignored) {}
                }
            }
        }

        private static double midiToHz(int midi) {
            return 440.0 * Math.pow(2.0, (midi - 69) / 12.0);
        }

        private static double staccatoEnv(int i, int n) {
            int attack = Math.max(1, (int) (n * 0.06));
            int decay  = Math.max(1, (int) (n * 0.18));
            int cut    = (int) (n * 0.78);

            if (i < attack) return i / (double) attack;
            if (i < decay)  return 1.0 - (i - attack) / (double) Math.max(1, (decay - attack)) * 0.35;
            if (i > cut)    return 0.0;
            return 0.65;
        }

        private static double triangleFromPhase(double phase) {
            return (2.0 / Math.PI) * Math.asin(Math.sin(phase));
        }

        private static double clamp01(double v) {
            return Math.max(0.0, Math.min(1.0, v));
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}