# VisionAgent

VisionAgent is an AI-powered Android accessibility system that enables blind and low-vision users to operate mobile apps through natural speech. The system combines on-device screen understanding, conversational guidance, and automated UI actions so a user can simply state their goal (e.g., "Book a cab home") and the agent completes the required steps inside apps like Uber Lite, Zomato, WhatsApp, etc.

## What It Does
- Captures the current screen and interprets UI elements using multimodal vision models.
- Holds a natural speech conversation to clarify user intent.
- Plans the sequence of UI actions required to achieve the goal.
- Executes taps, swipes, text input, and confirmations automatically via the accessibility/ADB bridge.
- Provides spoken feedback at every step to ensure transparency and control.

## Why This Matters
While screen readers assist with navigation, many essential apps are still visually complex and slow to use without sight. VisionAgent reduces the cognitive and manual effort required to complete everyday digital tasks, supporting autonomy in communication, transportation, payments, and services.

## Architecture (High-Level)
1. **Speech Input** → Voice capture and transcription.
2. **Task Understanding** → Intent parsing + contextual reasoning.
3. **Screen Perception** → Vision model identifies actionable UI elements.
4. **Action Planner** → Translates intent + screen state into step-by-step operations.
5. **Action Executor** → Performs UI actions via Accessibility/ADB.
6. **Speech Output** → Real-time spoken updates and confirmations.

## Current Status
- Core action loop functional on Android with basic app workflows.
- Active testing with visually impaired users and accessibility educators.
- Ongoing dataset and heuristic refinement for UI element detection and task planning.

## Target Users
- Blind and low-vision individuals.
- Accessibility training centers and NGOs.
- Researchers and developers working on assistive agents and HCI.

## Roadmap
- Expand supported app workflows (transport, messaging, payments).
- Improve error recovery in multi-step task execution.
- Release educational modules and documentation for training/learning use cases.
- Pilot deployment with partner accessibility organizations.

## Requirements (Developer Setup)
- Android device or emulator with ADB enabled
- Android AccessibilityService permissions
- Access to a multimodal vision API (Gemini recommended)

## Contributing
We welcome contributions focused on:
- UI element detection in low-contrast or dynamic screens
- Robust planning policies for multi-screen flows
- Speech interaction and dialogue turn design
- Usability testing frameworks for accessibility research

## License
To be determined based on partner requirements (MIT/Apache/Custom).

---

For collaboration or research partnerships, contact: **teamplanb01@gmail.com**
