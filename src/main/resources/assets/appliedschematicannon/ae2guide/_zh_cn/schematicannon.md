---
navigation:
  title: Applied Schematicannon
  icon: create:schematicannon
  position: 60
item_ids:
- create:schematicannon
---

# Applied Schematicannon

## 蓝图加农炮

<Row gap="20">
<BlockImage id="create:schematicannon" scale="8" />
</Row>

[蓝图加农炮](https://github.com/Creators-of-Create/Create)是 Create 的机器，会按照已部署的蓝图逐块建造，
所需材料从紧邻它的容器中提取。

安装 Applied Schematicannon 后，紧贴炮台的 ME 接口也会被视作这样的容器，炮台可以直接用 ME 网络里的材料建造。
想让它在材料不足时自动合成，请在该接口中装入**合成卡**。

## 从 ME 网络请求物品

当炮台需要某种网络里没有的材料时，它会请网络合成：

* 紧贴炮台的接口必须装有**合成卡**；
* 网络需要有**合成 CPU**，以及该物品的**已编码样板**；
* 合成出来的物品会进入 ME 网络，炮台再从网络中取走；
* 若炮台开启了「跳过缺少的方块」，它不会发出任何请求，只会跳过拿不到的材料。

样板编码与合成 CPU 的搭建方式，参见[自动合成](ae2:ae2-mechanics/autocrafting.md)页面。
