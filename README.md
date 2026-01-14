# Focus Time Tracker for IntelliJ IDEA

Track how much time you spend focused in IntelliJ IDEA with detailed statistics and visualizations.

## Features

- **Automatic tracking** - Tracks when IntelliJ window is in focus
- **Daily statistics** - See your focus time for today
- **Session tracking** - Track current coding session duration
- **Weekly chart** - Visual bar chart of last 7 days
- **Monthly heatmap** - GitHub-style activity visualization for 30 days
- **Status bar widget** - Quick glance at today's focus time
- **Persistent storage** - Data saved between sessions

## Installation

### From Built Plugin

1. Build the plugin: `./gradlew buildPlugin`
2. The plugin ZIP will be in `build/distributions/`
3. In IntelliJ: Settings → Plugins → ⚙️ → Install Plugin from Disk
4. Select the ZIP file and restart IDE

### Development

```bash
# Run IDE with plugin for testing
./gradlew runIde

# Build plugin distribution
./gradlew buildPlugin
```

## Usage

1. Open the "Focus Tracker" tool window:
   - View → Tool Windows → Focus Tracker
   - Or click the timer in the status bar

2. The dashboard shows:
   - Current session time
   - Today's total focus time
   - Weekly bar chart
   - Monthly activity heatmap

## Screenshots

### Dashboard
The dashboard provides at-a-glance statistics:
- Today's focus time with live updates
- Current session duration
- 7-day bar chart showing daily focus time
- 30-day heatmap (GitHub-style) showing activity intensity

### Status Bar Widget
A compact widget in the status bar shows today's total time and tracking status.

## Data Storage

Focus time data is stored in:
- macOS: `~/Library/Application Support/JetBrains/<product>/options/focusTimeTracker.xml`
- Linux: `~/.config/JetBrains/<product>/options/focusTimeTracker.xml`
- Windows: `%APPDATA%\JetBrains\<product>\options\focusTimeTracker.xml`

## Requirements

- IntelliJ IDEA 2023.3 or later (or any JetBrains IDE based on IntelliJ Platform)
- Java 17+

## Build Requirements

- JDK 17+
- Gradle 8.x (wrapper included)

## License

MIT License
