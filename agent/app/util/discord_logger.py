import os
import datetime
import requests
from dotenv import load_dotenv

load_dotenv()

DISCORD_API_URL = os.getenv("DISCORD_API_URL")

#디스코드 채널로 메시지 전송
def discord_send_message(title: str, description: str, url: str | None, color: int):
    message = {
        "embeds": [{
            "title": title,
            "description": description,
            "url": url,
            "color": color,
            "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat()
        }]
    }
    requests.post(DISCORD_API_URL, json=message, timeout=5)
