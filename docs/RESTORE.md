# Restore Jarvis2.0 on Clean Linux

## 1) Install dependencies
```bash
# Ubuntu/Debian example
sudo apt update
sudo apt install -y openjdk-21-jdk maven docker.io docker-compose-plugin kubectl
# Optional local cluster runtime
# curl -sfL https://get.k3s.io | sh -
```

## 2) Clone repository
```bash
git clone git@github.com:<github-user>/<repo>.git
cd <repo>
```

## 3) Build
```bash
mvn -q -DskipTests package
```

## 4) Configure secrets
```bash
mkdir -p ~/.jarvis/secrets
cp ./secrets/secrets.example.env ~/.jarvis/secrets/secrets.env
chmod 600 ~/.jarvis/secrets/secrets.env
./scripts/product/jarvis-secrets-apply.sh
```

## 5) Configure TLS and hosts
```bash
./scripts/product/jarvis-generate-certs.sh
sudo ./scripts/product/jarvis-install-tls.sh
sudo ./scripts/product/jarvis-setup-hosts.sh
```

## 6) Launch
```bash
./jarvis-launch.sh
# Optional features
ENABLE_LLM=true ENABLE_MEMORY=true ./jarvis-launch.sh
```

## 7) Install desktop shortcut (optional)
```bash
./scripts/product/jarvis-desktop-install.sh
```

## 8) Logs
```bash
~/.jarvis/logs
```
