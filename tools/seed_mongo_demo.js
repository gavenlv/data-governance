// MongoDB 演示数据（用于验证 MongoDB 连接器的 schema 推断与采集）
// 用法：docker cp tools/_mongo_seed.js dg-mongo:/tmp/seed.js && docker exec dg-mongo mongosh --quiet --file /tmp/seed.js
const s = db.getSiblingDB("shop");
s.customers.drop();
s.orders.drop();
s.order_items.drop();
s.inventory.drop();

s.customers.insertMany([
  { _id: 1, name: "Alice", email: "alice@example.com", tier: "GOLD", created_at: new Date("2024-01-05T00:00:00Z"),
    address: { city: "Shanghai", zip: "200000" }, tags: ["vip", "early"], credit_limit: 50000.5 },
  { _id: 2, name: "Bob", email: "bob@example.com", tier: "SILVER", created_at: new Date("2024-02-11T00:00:00Z"),
    address: { city: "Beijing", zip: "100000" }, tags: ["new"], credit_limit: 10000 },
  { _id: 3, name: "Carol", email: null, tier: "GOLD", created_at: new Date("2024-03-02T00:00:00Z"),
    address: { city: "Shenzhen", zip: "518000" }, tags: [], credit_limit: null },
  // 第 4 条刻意制造两种"该被看见"的信号：
  //   1) email 从 string 变成 number → 类型不稳定（跨族），必须暴露为 mixed(...)
  //   2) loyalty_score 只在部分文档出现 → 稀疏字段，必须写入覆盖度
  { _id: 4, name: "Dave", email: 12345, tier: "BRONZE", created_at: new Date("2024-04-20T00:00:00Z"),
    address: { city: "Hangzhou" }, tags: ["new", "trial"], credit_limit: 5000, loyalty_score: 42 }
]);

for (let i = 1; i <= 40; i++) {
  s.orders.insertOne({
    _id: i,
    customer_id: (i % 3) + 1,
    amount: Math.round(Math.random() * 1000 * 100) / 100,
    currency: "CNY",
    status: ["PAID", "PENDING", "REFUNDED"][i % 3],
    created_at: new Date(Date.UTC(2024, i % 12, (i % 27) + 1)),
    items_count: (i % 5) + 1
  });
}

for (let i = 1; i <= 90; i++) {
  s.order_items.insertOne({
    _id: i,
    order_id: (i % 40) + 1,
    sku: "SKU-" + (i % 12),
    qty: (i % 4) + 1,
    price: Math.round(Math.random() * 200 * 100) / 100
  });
}

s.inventory.insertOne({ _id: "SKU-1", warehouse: "WH-A", on_hand: 120, updated_at: new Date() });

print(JSON.stringify({
  customers: s.customers.countDocuments(),
  orders: s.orders.countDocuments(),
  order_items: s.order_items.countDocuments(),
  inventory: s.inventory.countDocuments()
}));
