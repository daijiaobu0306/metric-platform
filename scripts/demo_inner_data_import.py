#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
测试脚本：对内部人员 - 步骤响应数据导入

演示脚本技能的功能：
1. 读取原始数据文件（本例使用模拟数据）
2. 解析数据
3. 计算指标
4. 写入数据库

版本: 1.0
作者: AI生成测试
创建时间: 2026-05-12
"""

import sys
import os

def main():
    """
    主函数 - 标准入口
    平台通过此函数调用脚本
    """
    try:
        # =========================================
        # 1. 连接数据库（从环境变量获取配置）
        # =========================================
        import pymysql
        conn = pymysql.connect(
            host=os.environ.get('DB_HOST', 'localhost'),
            port=int(os.environ.get('DB_PORT', 3306)),
            user=os.environ.get('DB_USER', 'root'),
            password=os.environ.get('DB_PASSWORD', ''),
            database=os.environ.get('DB_NAME', 'system_operations_metrics'),
            charset='utf8mb4'
        )
        print(f"Connected to database: {os.environ.get('DB_NAME', 'system_operations_metrics')}")

        # =========================================
        # 2. 模拟读取原始数据（实际应从Excel/CSV读取）
        # 本演示使用模拟数据
        # =========================================
        # 原始数据格式：
        # 日期, 小类, 业务名称, 步骤名称, 步骤类型, 访问次数, 平均响应(秒), 间隔(秒)
        mock_data = [
            ('2026-05-12', '查询类', '精准营销查询', '页面加载', 'query', 1520, 1.25, 300.5),
            ('2026-05-12', '查询类', '客户列表查询', '页面加载', 'query', 2350, 2.10, 180.3),
            ('2026-05-12', '办理类-单业务', '缴费办理', '步骤1-选择号码', 'crm', 580, 0.85, 120.0),
            ('2026-05-12', '办理类-单业务', '缴费办理', '步骤2-确认信息', 'crm', 520, 1.20, 90.5),
            ('2026-05-12', '办理类-单业务', '缴费办理', '步骤3-支付', 'crm', 480, 2.50, 60.2),
            ('2026-05-12', '办理类-融合业务', '携号转网', '步骤1-资格查询', 'chbn', 320, 3.20, 240.0),
            ('2026-05-12', '办理类-融合业务', '携号转网', '步骤2-套餐选择', 'chbn', 280, 5.50, 180.0),
            ('2026-05-12', '办理类-融合业务', '携号转网', '步骤3-实名认证', 'chbn', 250, 4.30, 120.0),
        ]

        print(f"Loaded {len(mock_data)} records from data source")

        # =========================================
        # 3. 数据处理与计算
        # =========================================
        records_to_insert = []
        for row in mock_data:
            data_date, sub_category, business_name, step_name, step_type, access_count, avg_response, interval = row
            records_to_insert.append({
                'data_date': data_date,
                'sub_category': sub_category,
                'business_name': business_name,
                'step_name': step_name,
                'step_type': step_type,
                'access_count': access_count,
                'avg_response_sec': avg_response,
                'interval_sec': interval,
            })

        # =========================================
        # 4. 写入数据库
        # =========================================
        with conn.cursor() as cursor:
            sql = """INSERT INTO inner_step_response
                     (data_date, sub_category, business_name, step_name, step_type, access_count, avg_response_sec, interval_sec)
                     VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                     ON DUPLICATE KEY UPDATE
                     access_count = VALUES(access_count),
                     avg_response_sec = VALUES(avg_response_sec),
                     interval_sec = VALUES(interval_sec)"""

            for r in records_to_insert:
                cursor.execute(sql, (
                    r['data_date'],
                    r['sub_category'],
                    r['business_name'],
                    r['step_name'],
                    r['step_type'],
                    r['access_count'],
                    r['avg_response_sec'],
                    r['interval_sec']
                ))
        conn.commit()
        print(f"Inserted {len(records_to_insert)} records into inner_step_response")

        # =========================================
        # 5. 返回执行结果
        # =========================================
        result = {
            'success': True,
            'message': f'Successfully processed {len(records_to_insert)} records',
            'records_processed': len(records_to_insert),
            'data_date': '2026-05-12'
        }
        print(f"Result: {result}")
        return result

    except Exception as e:
        result = {
            'success': False,
            'message': str(e),
            'records_processed': 0
        }
        print(f"Error: {result}")
        return result

    finally:
        if 'conn' in dir():
            conn.close()
            print("Database connection closed")


if __name__ == '__main__':
    result = main()
    print("\n=== Final Result ===")
    print(result)
    sys.exit(0 if result.get('success') else 1)
