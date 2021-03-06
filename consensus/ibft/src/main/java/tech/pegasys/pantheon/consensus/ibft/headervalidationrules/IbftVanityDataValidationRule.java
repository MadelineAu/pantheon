/*
 * Copyright 2019 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.pantheon.consensus.ibft.headervalidationrules;

import tech.pegasys.pantheon.consensus.ibft.IbftContext;
import tech.pegasys.pantheon.consensus.ibft.IbftExtraData;
import tech.pegasys.pantheon.ethereum.ProtocolContext;
import tech.pegasys.pantheon.ethereum.core.BlockHeader;
import tech.pegasys.pantheon.ethereum.mainnet.AttachedBlockHeaderValidationRule;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class IbftVanityDataValidationRule
    implements AttachedBlockHeaderValidationRule<IbftContext> {

  private static final Logger LOG = LogManager.getLogger();

  @Override
  public boolean validate(
      final BlockHeader header,
      final BlockHeader parent,
      final ProtocolContext<IbftContext> protocolContext) {
    final IbftExtraData extraData = IbftExtraData.decode(header.getExtraData());

    if (extraData.getVanityData().size() != IbftExtraData.EXTRA_VANITY_LENGTH) {
      LOG.trace("Ibft Extra Data does not contain 32 bytes of vanity data.");
      return false;
    }
    return true;
  }
}
