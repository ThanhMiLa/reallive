FROM bluenviron/mediamtx:1.21.2 AS media

FROM python:3.13-alpine
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY --from=media /mediamtx /mediamtx
COPY media-server/hooks/media_hook.py /hooks/media_hook.py
COPY media-server/hooks/viewer_hook.py /hooks/viewer_hook.py
COPY media-server/hooks/media_entrypoint.py /hooks/media_entrypoint.py
ENTRYPOINT ["python3", "/hooks/media_entrypoint.py"]
CMD ["/mediamtx", "/mediamtx.yml"]
