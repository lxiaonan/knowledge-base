"""Create local configuration without printing credentials or overwriting an existing .env."""
from pathlib import Path
import secrets

root=Path(__file__).resolve().parents[1]
destination=root/'.env'
if destination.exists():
    print('Existing .env retained. No values changed.')
else:
    content=(root/'.env.example').read_text(encoding='utf-8')
    content=content.replace('replace-with-a-strong-database-password',secrets.token_urlsafe(30))
    content=content.replace('replace-with-a-strong-admin-password',secrets.token_urlsafe(24))
    destination.write_text(content,encoding='utf-8')
    print('Created .env with generated database and bootstrap passwords; AI_API_KEY is empty.')
