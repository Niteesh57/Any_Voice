FROM python:3.12-slim-bookworm
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
ENV LD_LIBRARY_PATH=/usr/local/lib/python3.12/site-packages/agora/agora_sdk
RUN apt-get update && apt-get install -y --no-install-recommends libasound2 libglib2.0-0 libx11-6 libxext6 ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements*.txt ./
RUN pip install --no-cache-dir -r requirements-worker.txt
COPY patch_sdk.py ./patch_sdk.py
RUN python patch_sdk.py
COPY anyvoice ./anyvoice
RUN useradd --create-home worker && chown -R worker:worker /app
USER worker
EXPOSE 8000
CMD ["uvicorn", "anyvoice.api:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--no-access-log"]
