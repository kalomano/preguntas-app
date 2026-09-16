from __future__ import annotations

import argparse
import json
import os
import sqlite3

import psycopg


def main():
    p = argparse.ArgumentParser(description="Migra questions.db local a Supabase/PostgreSQL")
    p.add_argument("sqlite_db", help="Ruta a data/questions.db")
    args = p.parse_args()
    url = os.environ.get("DATABASE_URL", "").strip()
    if not url:
        raise SystemExit("Falta DATABASE_URL. En Windows: set DATABASE_URL=tu_conexion")

    src = sqlite3.connect(args.sqlite_db)
    src.row_factory = sqlite3.Row
    rows = src.execute("SELECT question, options_json, correct_index FROM questions ORDER BY id").fetchall()
    src.close()

    with psycopg.connect(url) as dst:
        dst.execute("""CREATE TABLE IF NOT EXISTS questions(
            id BIGSERIAL PRIMARY KEY,
            question TEXT NOT NULL,
            options_json TEXT NOT NULL,
            correct_index INTEGER NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )""")
        inserted = 0
        for r in rows:
            exists = dst.execute("SELECT 1 FROM questions WHERE question=%s LIMIT 1", (r["question"],)).fetchone()
            if exists:
                continue
            dst.execute(
                "INSERT INTO questions(question, options_json, correct_index) VALUES(%s,%s,%s)",
                (r["question"], r["options_json"], int(r["correct_index"])),
            )
            inserted += 1
    print(f"Listo. Leídas: {len(rows)} | nuevas insertadas: {inserted} | duplicadas omitidas: {len(rows)-inserted}")


if __name__ == "__main__":
    main()
