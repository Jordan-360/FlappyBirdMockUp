# 🐦 FlappyFX — JavaFX Game Engine Project

A fully polished **2D arcade game built from scratch in Java using JavaFX**.  
No external game engines, no sprite packs, no audio files — every system is implemented programmatically.

This project demonstrates **real-time rendering, physics simulation, procedural graphics, audio synthesis, and full game-state management** in a single, production-ready application.

---

## 🎮 Features

### Gameplay
- Classic Flappy Bird–style mechanics
- Smooth physics-based movement
- Difficulty scaling over time (pipe speed, gap size, spawn rate)
- Start screen, pause, and restart states
- Pixel-perfect centered UI overlays

### Rendering & Graphics
- Custom game loop using `AnimationTimer`
- Procedurally drawn bird with:
  - Wing-flap animation
  - Rotation based on velocity
  - Transparent rendering (no sprite artifacts)
- Procedurally generated pipes and scrolling ground
- Particle effects for flapping, scoring, and crashing
- Optional hitbox visualization (debug mode)

### Audio
- Fully synthesized sound effects (no audio files)
  - Flap
  - Score
  - Collision
- Mario-style chiptune background music generated in real time
- Master volume slider (controls music + SFX)
- Mute toggle

### Systems & Architecture
- Clean state management (`START`, `PLAYING`, `PAUSED`, `GAME_OVER`)
- Accurate collision detection (circle vs rectangle)
- Persistent high-score storage
- Frame-rate–independent physics
- Keyboard-based input handling

---

## 🛠️ Tech Stack

- **Language:** Java 17  
- **Framework:** JavaFX  
- **Audio:** Java Sound API  
- **Build Tool:** Maven  
- **Graphics:** JavaFX Canvas API  

---

## 🎹 Controls

| Key | Action |
|----|-------|
| Space / ↑ | Flap / Start game |
| P | Pause / Resume |
| R | Restart |
| H | Toggle hitboxes |
| M | Mute audio |

Volume can be adjusted using the on-screen slider.

---
## 🧠 What This Project Demonstrates

Strong Java fundamentals

Real-time rendering and physics systems

Procedural graphics and animation

Audio synthesis and threading

Game-state architecture and input handling

Building complete, shippable software from scratch


## ▶️ Running the Project

### Prerequisites
- Java **17** (recommended)
- Maven

### Run
```bash
mvn clean javafx:run


