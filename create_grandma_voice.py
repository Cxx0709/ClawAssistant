import urllib.request
import urllib.error
import json
import time

api_key = "sk-ws-H.PMRDEMM.oka6.MEUCIQCWwSfZLXSyY5YLLPkG3yc3mFN1LrGJRuMESyLXj6LG1gIgeeiPzkvFfHH35H2b1w7XwN5PYFK6ZHeOLalo7t1KyvE"
url = "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/customization"

headers = {
    "Authorization": f"Bearer {api_key}",
    "Content-Type": "application/json"
}

payload = {
    "model": "voice-enrollment",
    "input": {
        "prompt": "年长女性，音调慈祥略颤，语速稍慢，语气温柔疼爱，如哄孙辈般柔和，充满关怀感，带着浓浓的亲情。",
        "target_model": "cosyvoice-v3.5-plus",
        "voice_name": "慈祥奶奶"
    }
}

print("正在调用 voice-enrollment 创建老奶奶声音...")
print(f"请求 URL: {url}")
print(f"请求体: {json.dumps(payload, ensure_ascii=False, indent=2)}")
print()

try:
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    response = urllib.request.urlopen(req, timeout=60)
    response_text = response.read().decode("utf-8")
    print(f"状态码: {response.status}")
    print(f"响应: {response_text}")
    print()

    result = json.loads(response_text)
    output = result.get("output", {})
    task_id = output.get("task_id")
    task_status = output.get("task_status")

    print(f"任务 ID: {task_id}")
    print(f"任务状态: {task_status}")

    if task_id:
        poll_url = f"{url}/{task_id}"
        print(f"\n开始轮询任务状态...")
        for i in range(30):
            time.sleep(2)
            poll_req = urllib.request.Request(poll_url, headers=headers, method="GET")
            poll_response = urllib.request.urlopen(poll_req, timeout=30)
            poll_text = poll_response.read().decode("utf-8")
            poll_data = json.loads(poll_text)
            poll_output = poll_data.get("output", {})
            status = poll_output.get("task_status")
            voice_id = poll_output.get("voice_id")

            print(f"[{i+1}/30] 状态: {status} | voice_id: {voice_id}")

            if status == "SUCCEEDED" and voice_id:
                print(f"\n🎉 老奶奶声音创建成功！")
                print(f"voice_id: {voice_id}")
                with open(r"C:\Users\han\ClawAssistant\grandma_voice_id.txt", "w") as f:
                    f.write(voice_id)
                print(f"已保存到 grandma_voice_id.txt")
                break
            elif status == "FAILED":
                print(f"\n❌ 声音创建失败")
                print(f"错误: {poll_output.get('message', '未知错误')}")
                break

except urllib.error.HTTPError as e:
    print(f"❌ HTTP 错误: {e.code}")
    print(f"响应: {e.read().decode('utf-8')}")
except Exception as e:
    print(f"❌ 异常: {e}")
    import traceback
    traceback.print_exc()
