# JChatGPT

## 用法

在群内直接@bot即可触发对话

你也可以通过引用群友消息+@bot来让Bot识别引用消息的内容

回复bot的消息即可引用对应的上下文对话（包括这个回复的历史对话）

## 权限列表
- `JChatGPT:Chat` 拥有该权限即可使用bot与ChatGPT对话
- `top.jie65535.mirai.jchatgpt:command.jgpt` 拥有该权限即可使用`/jgpt`相关命令

## 命令列表
- `/jgpt setToken <token>` - 设置OpenAI API Token
- `/jgpt enable <contact>` - 启用目标对话权限
- `/jgpt disable <contact>` - 禁用目标对话权限
- `/jgpt reload` - 重载配置文件

## 配置文件

`./config/top.jie65535.mirai.JChatGPT/Config.yml`
```yaml
# OpenAI API base url
openAiApi: 'https://api.openai.com/v1/'
# OpenAI API Token
openAiToken: ''
# Chat模型
chatModel: 'gpt-3.5-turbo-1106'
# Chat默认提示
prompt: ''
# 群管理是否自动拥有对话权限，默认是
groupOpHasChatPermission: true
# 好友是否自动拥有对话权限，默认是
friendHasChatPermission: true
```

## 备注

如果默认的openai api调用失败，可以换个镜像地址。

如果有必要，后续可以增加代理设置。