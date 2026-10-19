# FPS Lite

Contador de FPS flutuante, leve, via root (Kotlin, sem bibliotecas).

- Abra o app, toque em **Ligar**, abra o jogo. Arraste o número para mudar de lugar.
- Desligar: botão no app ou "Desligar" na notificação.
- Sem teto de 120: mostra o que o SurfaceFlinger apresentou (limitado pela tela).

## Gerar o APK (GitHub Actions)
1. Suba esta pasta para um repositório (git push).
2. Aba **Actions** > último build > **Artifacts** > `FpsLite-apk`.
3. Se já tiver uma versão instalada de outro build, desinstale antes (a chave debug muda a cada build).
