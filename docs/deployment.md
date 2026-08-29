# Deployment

How a commit becomes the thing players connect to. Everything here is the OVH VPS in front of it;
`operations.md` is what to do once it is running.

---

## The shape of it

```
git push origin v0.2.0
        │
        ├─ verify    ./gradlew build                        the tagged commit, not main
        ├─ publish   ghcr.io/korobetski/tto-server@sha256:…  built once, from that commit
        └─ deploy    ssh → /srv/tto/scripts/deploy.sh        pull, restart, wait, roll back
```

Three properties are worth stating, because each one is a decision that could have gone otherwise:

- **A tag deploys, a merge does not.** `main` is where work becomes releasable. A tag is where
  somebody decides this one goes out, at a moment when they are looking.
- **The image is built once.** The host pulls the digest CI published. It never builds, so the
  running code and the tested code cannot differ.
- **A deployment that does not answer `/health/ready` is undone.** `scripts/deploy.sh` waits, and
  restores the previous digest if the new one never becomes ready. The release goes red; the players
  see nothing.

---

## Provisioning the VPS

Once, on a fresh Debian 12 or Ubuntu 24.04 image. Everything below is run as root over SSH unless
it says otherwise.

### 1. DNS first

Point an `A` record at the VPS's public address — and an `AAAA` at its IPv6, if OVH gave you one —
and wait for it to resolve. This is genuinely first: Caddy asks Let's Encrypt for a certificate the
moment it starts, and a validation against a name that does not resolve yet counts against a rate
limit measured in hours.

```
dig +short tto.example.com
```

**A name is required, not a preference.** ACME validates a domain and never a bare address, so an
IP alone cannot be given a publicly trusted certificate — and this server carries accounts and
passwords, which rules out serving it in the clear. If the domain is not bought yet, steps 2 to 7
do not depend on it and can be done now; only this one and the first `docker compose up` have to
wait. A generic-DNS service such as `sslip.io` — where `<your-ip>.sslip.io` resolves to that
address, and Let's Encrypt will issue for it — is a working stopgap, at the price of an address
that contains the IP, so that changing VPS changes the address every player has saved.

### 2. A user for deployments

```
adduser --disabled-password --gecos "" deploy
usermod -aG docker deploy          # after step 3 installs the group
mkdir -p /home/deploy/.ssh && chmod 700 /home/deploy/.ssh
```

Generate the key pair **on your machine**, not on the VPS — the private half should never exist
there:

```bash
ssh-keygen -t ed25519 -C "tto-server deploy" -f ~/.ssh/tto_deploy
```

Put the public half in `/home/deploy/.ssh/authorized_keys` (`chmod 600`, owned by `deploy`), and
keep the private half for the `VPS_SSH_KEY` secret below. It is a deployment key: it belongs to CI,
not to you, and it is the one thing that gets rotated if a runner is ever compromised.

Note what `usermod -aG docker` means: membership of the `docker` group is equivalent to root on this
host, because the daemon runs as root and will mount anything you ask it to. That is the standard
arrangement for a single-purpose host and it is still worth knowing, rather than discovering.

### 3. Docker

From Docker's own repository, not the distribution's — the distribution's `docker.io` lags, and the
compose plugin this project uses is not always in it.

```bash
curl -fsSL https://get.docker.com | sh
```

Piping a script from the network into a shell is exactly the thing this project's Dockerfile
comments are careful about, so: read it first if you would like to (`curl -fsSL
https://get.docker.com | less`), or follow Docker's manual apt instructions instead. It is listed
here because it is what Docker themselves publish for this case.

### 4. The firewall

Three ports, and nothing else. The database and the server publish nothing — `compose.prod.yaml`
gives them no `ports:` at all — but a firewall is what makes that true rather than merely intended,
and it is what stops the next container somebody adds from being reachable by accident.

```bash
ufw default deny incoming
ufw default allow outgoing
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 443/udp
ufw enable
```

⚠️ `ufw allow OpenSSH` before `ufw enable`, in that order. Reversed, it locks you out of the host
you are typing on, and the way back in is OVH's KVM console.

### 5. Unattended security updates

A host that is patched when somebody remembers is a host that is not patched.

```bash
apt install -y unattended-upgrades
dpkg-reconfigure -plow unattended-upgrades
```

### 6. The deployment directory

```bash
install -d -o deploy -g deploy /srv/tto /srv/tto/backups
```

As `deploy`, put the environment in place:

```bash
# from your machine
scp .env.prod.sample deploy@tto.example.com:/srv/tto/.env
ssh deploy@tto.example.com 'chmod 600 /srv/tto/.env'
```

Then edit `/srv/tto/.env` on the host and fill in every blank. Generate each password there and
reuse none of them:

```bash
openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | head -c 32; echo
```

The `tr` is not decoration. Compose expands `$` and treats `#` as a comment, so a generated password
containing either fails in a way that looks exactly like a wrong password.

One blank is not generated here and has to be fetched first: `BREVO_API_KEY`, from the Brevo
dashboard. `compose.prod.yaml` states it with `:?`, so without it the stack refuses to start rather
than starting a server that would write confirmation codes into its log. `MAIL_FROM` must be a
sender Brevo has verified, on a subdomain whose SPF, DKIM and DMARC records are in place — do that
before the first deployment, not after the first player cannot reset a password.

Changing any of these later is `docs/operations.md` § *Changing a value on the deployed host*; the
short version is that `docker compose restart` does **not** re-read this file and `up -d server`
does.

### 7. Let the host read the registry

The image is private, so the host needs a token to pull it: a GitHub PAT with `read:packages` and
nothing else.

```bash
# as deploy, on the VPS
echo 'ghp_…' | docker login ghcr.io -u korobetski --password-stdin
```

This writes `~/.docker/config.json` once and is not part of any deployment. The alternative is
making the package public, which removes this step and publishes every build of the server to
anyone who looks — a trade worth making deliberately, if at all.

### 8. The first deployment

There is nothing to pull yet, so tag a release and let the workflow do it. If you would rather see
it happen by hand first:

```bash
cd /srv/tto
./scripts/deploy.sh ghcr.io/korobetski/tto-server:v0.1.0
docker compose -f compose.prod.yaml logs -f
```

Expect the first start to take a minute: Postgres initialises its data directory and runs
`docker/postgres/init/10-app-role.sh`, the server runs Flyway, and Caddy negotiates a certificate.

---

## The GitHub secrets

Repository → Settings → Secrets and variables → Actions.

| Secret | What it is |
|---|---|
| `VPS_HOST` | the DNS name or address of the VPS |
| `VPS_USER` | `deploy` |
| `VPS_PORT` | optional; omit unless SSH is not on 22 |
| `VPS_SSH_KEY` | the **private** half of the key from step 2, whole file including the header line |
| `VPS_KNOWN_HOSTS` | the VPS's host key, from `ssh-keyscan tto.example.com` |
| `GH_PACKAGES_TOKEN` | optional; a PAT with `read:packages`, for reading `com.tripletriad:core` |

`VPS_KNOWN_HOSTS` is the one that looks like a formality and is not. Without a pinned host key the
only two options are refusing to connect and accepting whatever answers — and the second means that
anyone who can influence DNS or routing between GitHub and OVH is handed a shell and the contents of
`.env`. Take it once, from a connection you trust:

```bash
ssh-keyscan -t ed25519 tto.example.com
```

`GH_PACKAGES_TOKEN` is needed only while the built-in `GITHUB_TOKEN` cannot read the `tto-core`
package. Granting that package read access to this repository — Package → Settings → Manage Actions
access — removes the need for it, and one fewer long-lived credential is worth the two minutes. See
`core-package.md` for what `tto-core` is and why it exists.

---

## Releasing

```bash
git tag -a v0.2.0 -m "What changed"
git push origin v0.2.0
```

Watch it in Actions. The release is green when `/health/ready` answered on the new digest, and red
in every other case — including the case where the deployment rolled itself back, which is a success
for the players and a failure for the release, and is reported as the latter on purpose.

### Rolling back

The previous images stay on the host, so this is instant and needs neither GitHub nor a network:

```bash
cd /srv/tto
./scripts/deploy.sh ghcr.io/korobetski/tto-server:v0.1.9
```

**A rollback does not undo a migration.** Flyway rolls forward only, and by the time the server
reported ready the schema had already changed. A release carrying a destructive migration therefore
needs a dump taken before it, by hand, and its rollback is a restore rather than this command. See
`operations.md` § Backups — which is still the least finished part of this project, and the part
that decides whether a bad evening is recoverable.

---

## What is still missing on this host

Named rather than implied:

- **Scheduled backups, off this machine.** `scripts/backup.sh` writes to `/srv/tto/backups`, on the
  same disk as the database, when somebody runs it. A cron entry and an off-site copy are the
  minimum, and a restore tested on a schedule is the only thing that makes them real.
- **Anything watching.** Nothing scrapes `/metrics` and nothing alerts. The server can be down for a
  day and the first report will come from a player.
- **A second instance.** Several decisions in this project are single-instance decisions and say so
  — in-process migration on start-up being the load-bearing one. Two containers behind Caddy would
  race Flyway on start-up before they did anything useful.
- **Log retention beyond the container.** Docker's json-file driver is capped at 100 MB per service
  in `compose.prod.yaml`, which bounds the disk and also bounds how far back anyone can look.
