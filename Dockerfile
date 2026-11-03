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
    ANTI_SOCIAL_YTDLP=/data/ytdlp/bin/yt-dlp \
    "ANTI_SOCIAL_YTDLP_UPDATE=uv pip install --python /data/ytdlp/bin/python --upgrade yt-dlp[default]" \
    DENO_DIR=/data/cache/deno \
    UV_CACHE_DIR=/data/cache/uv \
    XDG_CACHE_HOME=/data/cache \
    PYTHONUNBUFFERED=1

EXPOSE 8484
ENTRYPOINT ["/app/.venv/bin/python", "/app/docker-entrypoint.py"]
