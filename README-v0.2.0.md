# Bubble BDS Host v0.2.0

Arquitetura desta versão:

Android ARM64 → PRoot clássico (Termux) → Debian Trixie ARM64 → Box64 compilado no aparelho → BDS x86_64.

O Debian usado é o rootfs oficial `debian-trixie-aarch64-pd-v4.29.0.tar.xz` do projeto `termux/proot-distro`, com SHA-256:

`3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918`

O Box64 é fixado na tag `v0.4.4`, commit:

`2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a`

## Aplicar no projeto

```bash
cd ~/BubbleBDSHost
unzip -o ~/storage/downloads/BubbleBDSHost-v0.2.0-classic-proot.zip -d ~/BubbleBDSHost
chmod +x scripts/fetch_classic_proot.sh

git add .
git commit -m "v0.2.0 migrar para PRoot classico Debian Box64 local"
git push
```

O workflow novo não compila Box64 no GitHub. Ele só prepara o PRoot clássico ARM64 dentro do APK. O Box64 será compilado no primeiro preparo do app, dentro do Debian no telefone.

## Primeiro teste

É recomendado desinstalar a v0.1.x antes de instalar a v0.2.0 para liberar o Ubuntu antigo e evitar conflito de assinatura de APK debug. Desinstalar apaga os dados internos da versão anterior.

O primeiro preparo precisa de aproximadamente 1.3 GB livres e pode demorar porque instala `build-essential`, `cmake`, `git` e compila Box64 com `make -j2`.

Log esperado:

```text
[1/8] ✓ Android ARM64 detectado
[2/8] ✓ Espaço livre
[3/8] ✓ PRoot clássico presente no APK
[4/8] ✓ Debian Trixie ARM64 instalado
[debian] BUBBLE_PROOT_OK
[debian] aarch64
[5/8] ✓ Debian executando através do PRoot clássico
[6/8] ✓ Box64 compilado LOCALMENTE dentro do Debian
[7/8] ✓ BDS instalado
[8/8] ✓ Cadeia PRoot → Debian → Box64 → BDS validada
```

Esta é uma versão técnica de teste. Antes de distribuição pública/comercial, revise as obrigações das licenças dos componentes Termux/PRoot e demais bibliotecas redistribuídas.
