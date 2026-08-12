# Threat Model: Aegis AI Agent OS

## 1. Assets to Protect
- **User Data**: Files, messages, clipboard, and screen content.
- **API Keys**: Credentials for OpenAI, Anthropic, etc.
- **System Integrity**: Preventing the agent from performing unauthorized actions (e.g., deleting system files).

## 2. Potential Threats
- **Prompt Injection**: Malicious web content or messages trying to hijack the agent's instructions.
- **Insecure Storage**: API keys stored in plain text.
- **Over-privileged Tools**: Tools having more access than necessary (e.g., a calculator tool having internet access).
- **Man-in-the-Middle**: Insecure communication with AI providers.

## 3. Mitigation Strategies
- **Human-in-the-loop**: Mandatory approval for high-risk actions.
- **Android Keystore**: Encrypted storage for all secrets.
- **Sandbox Isolation**: Running arbitrary code in isolated processes.
- **Untrusted Content Separation**: Treating all external data as "untrusted" and preventing it from modifying the system prompt.
- **Capability-based Security**: Tools must request specific capabilities, which are granted based on user policy.
