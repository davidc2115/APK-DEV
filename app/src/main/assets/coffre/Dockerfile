# Coffre-fort JARVIS : Python (bibliothèque standard) + openssl pour le certificat auto-signé.
FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends openssl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY serveur.py /app/serveur.py
ENV COFFRE_DATA=/data COFFRE_PORT=8443 PYTHONUNBUFFERED=1
EXPOSE 8443
CMD ["python3", "/app/serveur.py"]
