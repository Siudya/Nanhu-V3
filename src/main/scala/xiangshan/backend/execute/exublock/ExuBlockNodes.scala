package xiangshan.backend.execute.exublock

import chipsalliance.rocketchip.config.Parameters
import chisel3.internal.sourceinfo.SourceInfo
import xiangshan.backend.execute.exu.ExuConfig
import xiangshan.backend.execute.exucx.{ExuComplexParam, ExuComplexWritebackNode}
import freechips.rocketchip.diplomacy.{AdapterNode, RenderedEdge, SimpleNodeImp, SinkNode, ValName}
import xiangshan.backend.issue.{IssueBundle, RsParam}

object ExuBlockIssueNodeImpl extends SimpleNodeImp[Seq[RsParam], ExuComplexParam, (RsParam, ExuComplexParam, Parameters), IssueBundle]{
  override def edge(pd: Seq[RsParam], pu: ExuComplexParam, p: Parameters, sourceInfo: SourceInfo): (RsParam, ExuComplexParam, Parameters) = {
    require(pu.isFpType || pu.isVecType || pu.isIntType || pu.isMemType)
    if (pu.isFpType) {
      (pd.filter(_.isFpRs).head, pu, p)
    } else if (pu.isVecType) {
      (pd.filter(_.isVecRs).head, pu, p)
    } else if (pu.isIntType) {
      (pd.filter(_.isIntRs).head, pu, p)
    } else {
      (pd.filter(_.isMemRs).head, pu, p)
    }
  }
  override def bundle(e: (RsParam, ExuComplexParam, Parameters)): IssueBundle = new IssueBundle(e._1.bankNum, e._1.entriesNum)(e._3)
  override def render(e: (RsParam, ExuComplexParam, Parameters)): RenderedEdge = RenderedEdge("#0000ff", e._1.TypeName + "Issue")
}

class ExuBlockWritebackNode(implicit valName: ValName) extends ExuComplexWritebackNode

class ExuBlockIssueNode(implicit valName: ValName) extends
  AdapterNode(ExuBlockIssueNodeImpl)({p => p}, {p => p})

class MemoryBlockIssueNode(cfg:(ExuConfig, Int))(implicit valName: ValName) extends SinkNode(ExuBlockIssueNodeImpl)(Seq(ExuComplexParam(cfg._2, Seq(cfg._1))))