"""Builds data/sample_store.db and data/csv/*.csv from a fixed random seed.

All names and numbers are synthetic. Run: python data/make_sample_store.py
"""

from __future__ import annotations

import csv
import random
import sqlite3
from datetime import date, timedelta
from pathlib import Path

HERE = Path(__file__).resolve().parent
DB_PATH = HERE / "sample_store.db"
CSV_DIR = HERE / "csv"

REGIONS = ["North", "South", "East", "West"]
FIRST = ["Ava", "Liam", "Noah", "Emma", "Mia", "Leo", "Zara", "Omar", "Iris", "Ravi", "Nina", "Theo",
         "Aria", "Kai", "Lena", "Sami", "Yuki", "Owen", "Maya", "Eli"]
LAST = ["Patel", "Nguyen", "Smith", "Chen", "Garcia", "Khan", "Brown", "Singh", "Lopez", "Kim"]
PRODUCTS = [
    ("Laptop Pro 14", "Electronics", 1299.00), ("Wireless Mouse", "Electronics", 29.99),
    ("USB-C Hub", "Electronics", 49.50), ("Noise Cancelling Headphones", "Electronics", 199.00),
    ("Desk Lamp", "Home", 39.00), ("Office Chair", "Home", 249.00), ("Standing Desk", "Home", 499.00),
    ("Coffee Maker", "Kitchen", 89.00), ("Chef Knife", "Kitchen", 59.00), ("Blender", "Kitchen", 79.00),
    ("Notebook Pack", "Stationery", 12.50), ("Gel Pens", "Stationery", 8.75),
    ("Backpack", "Accessories", 64.00), ("Water Bottle", "Accessories", 19.00),
    ("Running Shoes", "Sports", 119.00), ("Yoga Mat", "Sports", 35.00),
]
STATUSES = ["delivered"] * 8 + ["shipped", "cancelled"]

SCHEMA = """
CREATE TABLE customers (
    customer_id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    email TEXT NOT NULL,
    region TEXT NOT NULL,
    signup_date TEXT NOT NULL
);
CREATE TABLE products (
    product_id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    category TEXT NOT NULL,
    unit_price REAL NOT NULL
);
CREATE TABLE orders (
    order_id INTEGER PRIMARY KEY,
    customer_id INTEGER NOT NULL REFERENCES customers(customer_id),
    order_date TEXT NOT NULL,
    status TEXT NOT NULL,
    total REAL NOT NULL
);
CREATE TABLE order_items (
    item_id INTEGER PRIMARY KEY,
    order_id INTEGER NOT NULL REFERENCES orders(order_id),
    product_id INTEGER NOT NULL REFERENCES products(product_id),
    quantity INTEGER NOT NULL,
    unit_price REAL NOT NULL
);
"""


def build(seed: int = 3311) -> dict[str, list[tuple]]:
    rng = random.Random(seed)
    customers = []
    for cid in range(1, 121):
        name = f"{rng.choice(FIRST)} {rng.choice(LAST)}"
        email = f"{name.lower().replace(' ', '.')}{cid}@example.com"
        signup = date(2023, 1, 1) + timedelta(days=rng.randrange(0, 730))
        customers.append((cid, name, email, rng.choice(REGIONS), signup.isoformat()))
    products = [(i + 1, n, c, p) for i, (n, c, p) in enumerate(PRODUCTS)]

    orders, items = [], []
    item_id = 1
    start = date(2024, 1, 1)
    for oid in range(1, 1201):
        cust = rng.choice(customers)
        day = start + timedelta(days=rng.randrange(0, 731))
        lines = rng.randint(1, 4)
        total = 0.0
        for product in rng.sample(products, lines):
            qty = rng.choices([1, 2, 3, 4], weights=[60, 25, 10, 5])[0]
            items.append((item_id, oid, product[0], qty, product[3]))
            total += qty * product[3]
            item_id += 1
        orders.append((oid, cust[0], day.isoformat(), rng.choice(STATUSES), round(total, 2)))
    return {"customers": customers, "products": products, "orders": orders, "order_items": items}


def write_db(tables: dict[str, list[tuple]]) -> None:
    if DB_PATH.exists():
        DB_PATH.unlink()
    conn = sqlite3.connect(DB_PATH)
    conn.executescript(SCHEMA)
    for name, rows in tables.items():
        marks = ",".join("?" * len(rows[0]))
        conn.executemany(f"INSERT INTO {name} VALUES ({marks})", rows)
    conn.commit()
    conn.close()


def write_csv(tables: dict[str, list[tuple]]) -> None:
    CSV_DIR.mkdir(exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    for name in ("customers", "orders"):
        cursor = conn.execute(f"SELECT * FROM {name}")
        header = [d[0] for d in cursor.description]
        with (CSV_DIR / f"{name}.csv").open("w", newline="", encoding="utf-8") as handle:
            writer = csv.writer(handle)
            writer.writerow(header)
            writer.writerows(cursor.fetchall())
    conn.close()


if __name__ == "__main__":
    data = build()
    write_db(data)
    write_csv(data)
    print(f"Wrote {DB_PATH} and {CSV_DIR}")
