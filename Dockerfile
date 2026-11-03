FROM python:3.13-slim

RUN apt-get update && apt-get install -y --no-install-recommends tzdata && rm -rf /var/lib/apt/lists/*

COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv
# yt-dlp needs a JavaScript runtime to solve YouTube's challenges.
COPY --from=denoland/deno:bin /deno /usr/local/bin/deno

WORKDIR /app
COPY pyproject.toml uv.lock ./
COPY src ./src
RUN uv sync --frozen --no-dev --no-editable

COPY docker-entrypoint.py /app/docker-entrypoint.py
ENV ANTI_SOCIAL_DATA=/data \
    ANTI_SOCIAL_HOST=0.0.0.0 \
    ANTI_SOCIAL_YTDLP=/data/bin/yt-dlp \
    ANTI_SOCIAL_YTDLP_SELF_UPDATE=1 \
    DENO_DIR=/tmp/deno \
    XDG_CACHE_HOME=/tmp/cache \
    PYTHONUNBUFFERED=1

EXPOSE 8484
ENTRYPOINT ["/app/.venv/bin/python", "/app/docker-entrypoint.py"]
