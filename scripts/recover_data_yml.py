#!/usr/bin/env python3
"""
恢复 data.yml：把 tokenUsageDailyRecords 抽出成 token_usage.json，
顺手清理 tokenUsageRecords，把 data.yml 重写成合法的、yamlkt 能读回的 JSON。

用法（在 data.yml 所在目录运行）:
    python3 recover_data_yml.py /path/to/top.jie65535.mirai.JChatGPT/

会做：
    1. 备份原 data.yml -> data.yml.bak-<timestamp>
    2. 读 data.yml（按 JSON 解析，目前文件就是 JSON-flow YAML）
    3. 把 tokenUsageDailyRecords 写到 token_usage.json
    4. 删除 tokenUsageRecords 和 tokenUsageDailyRecords 字段
    5. 重写 data.yml（保留 contactMemory / userFavorability 等）
"""
import json
import os
import sys
import time

def main(target_dir: str) -> int:
    data_path = os.path.join(target_dir, "data.yml")
    if not os.path.exists(data_path):
        print(f"NOT FOUND: {data_path}", file=sys.stderr)
        return 1

    with open(data_path, "r", encoding="utf-8") as f:
        text = f.read()

    try:
        data = json.loads(text)
    except json.JSONDecodeError as e:
        print(f"data.yml 不是合法 JSON：{e}", file=sys.stderr)
        print("如果文件其实是 block-style YAML，请先用 yq/python yaml 转换", file=sys.stderr)
        return 2

    if not isinstance(data, dict):
        print(f"顶层不是 map，是 {type(data).__name__}", file=sys.stderr)
        return 3

    ts = int(time.time())
    backup_path = os.path.join(target_dir, f"data.yml.bak-{ts}")
    with open(backup_path, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"已备份 -> {backup_path}")

    daily_records = data.pop("tokenUsageDailyRecords", [])
    raw_records = data.pop("tokenUsageRecords", [])
    print(f"提取 tokenUsageDailyRecords: {len(daily_records)} 条")
    print(f"丢弃 tokenUsageRecords (legacy): {len(raw_records)} 条")

    token_path = os.path.join(target_dir, "token_usage.json")
    if os.path.exists(token_path):
        token_backup = os.path.join(target_dir, f"token_usage.json.bak-{ts}")
        os.rename(token_path, token_backup)
        print(f"已备份现有 token_usage.json -> {token_backup}")

    with open(token_path, "w", encoding="utf-8") as f:
        json.dump(daily_records, f, ensure_ascii=False, indent=2)
    print(f"写入 -> {token_path} ({len(daily_records)} 条)")

    with open(data_path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=4)
    print(f"重写 -> {data_path}（剩余字段: {list(data.keys())}）")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        sys.exit(1)
    sys.exit(main(sys.argv[1]))
