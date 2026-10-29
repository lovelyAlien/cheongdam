FROM python:3.13-slim
ENV TZ=Asia/Seoul PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY cheongdam_batch cheongdam_batch
CMD ["python", "-m", "cheongdam_batch"]
