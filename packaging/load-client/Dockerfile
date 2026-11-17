ARG PYTHON_BASE
FROM ${PYTHON_BASE}
ARG PYTHON_BASE
RUN printf '%s\n' "${PYTHON_BASE}" | grep -Eq '@sha256:[0-9a-f]{64}$' \
    || { echo "PYTHON_BASE must be pinned by digest (name@sha256:<64 hex>), got: '${PYTHON_BASE}'" >&2; exit 1; }
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /opt/load-client
COPY load_client.py /opt/load-client/load_client.py
USER 10001:10001
ENTRYPOINT ["python3", "/opt/load-client/load_client.py"]
CMD ["--help"]
