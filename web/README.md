# `web/` — where the portal is served from, and why it is empty here

Caddy serves this directory as static files. In production it is bind-mounted as `/srv/web`, and
the routing block in [`../Caddyfile`](../Caddyfile) reads `/srv/web/portal` — so `web/portal` is
the portal's document root, and everything under it is HTML, CSS, JavaScript and images that this
repository never builds and never ships.

It is empty in a fresh clone on purpose. The portal is a second repository, `tto-web`: an Astro
site with its own CI, which builds it and copies the result onto the VPS itself. Nothing about the
server release touches it — the tarball in `.github/workflows/release.yml` names five paths and
`web` is not one of them, and it extracts without deleting, which is exactly what lets two
repositories own different subdirectories of `/srv/tto`.

An empty directory is a working state, not a broken one. Every path the Caddyfile lists as the
portal's answers 404, every other path reaches the API, and that is the correct behaviour for a
host that has never had a portal deployed to it.

## On the VPS

    /srv/tto/web/releases/<commit-sha>/   one build, unpacked whole
    /srv/tto/web/portal -> releases/<sha> the symlink Caddy follows

The swap is a symlink move, so a transfer that dies halfway never serves a half-written site: the
new directory is complete before anything points at it.

**The link has to be relative.** Caddy resolves it inside the container, where the tree is mounted
at `/srv/web` and the host's `/srv/tto/web` does not exist — an absolute link would be written
correctly, look correct in `ls -l`, and resolve to nothing.

## Trying a build locally

Point `portal` at one and start the profile that has a proxy in it:

    ln -sfn /path/to/tto-web/dist web/portal
    docker compose --profile web up -d
    curl -si localhost:8081/fr/

`web/.gitignore` ignores everything here except itself and this file, so the link is yours alone
and cannot be committed by accident.
